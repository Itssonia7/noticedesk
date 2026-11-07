package com.noticedesk.api.controller;

import com.noticedesk.api.agent.NoticeTriageAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.exception.AppValidationException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TriageController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final AppProperties properties;
    private final NoticeTriageAgent noticeTriageAgent;

    // ---- POST /v1/notices/{id}/triage ----

    @PostMapping("/notices/{id}/triage")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> runTriage(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Load notice context
        var noticeRows = jdbc.queryForList(
                """
                SELECT n.notice_id, n.law, n.document_type, n.din_or_rfn, n.raw_extracted_json,
                       n.financial_year, n.assessment_year, COALESCE(n.issues, n.raw_extracted_json->>'issue') AS issue,
                       c.legal_name AS client_legal_name, c.pan AS client_pan,
                       r.identifier_value, r.registration_type
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                JOIN client_registrations r ON r.registration_id = n.registration_id
                WHERE n.notice_id = :nid AND c.deleted_at IS NULL
                """,
                Map.of("nid", id));

        if (noticeRows.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        Map<String, Object> noticeData = noticeRows.get(0);

        // Call triage agent synchronously
        Map<String, Object> triageInput = new HashMap<>(noticeData);
        triageInput.put("notice_id", id.toString());
        triageInput.put("tenant_id", tenantId);

        Map<String, Object> triageResult = noticeTriageAgent.generateTriage(triageInput);

        // Upsert to notice_triage
        String summary = (String) triageResult.getOrDefault("summary", "");
        String risk = (String) triageResult.getOrDefault("risk_level", "medium");
        String status = "ready_to_gather";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checklist = (List<Map<String, Object>>) triageResult.getOrDefault("checklist", List.of());

        UUID triageId = jdbc.queryForObject(
                """
                INSERT INTO notice_triage (notice_id, tenant_id, summary, risk_level, status)
                VALUES (:nid, :tid, :summary, :risk, :status)
                ON CONFLICT (notice_id) DO UPDATE
                    SET summary = EXCLUDED.summary,
                        risk_level = EXCLUDED.risk_level,
                        status = EXCLUDED.status,
                        updated_at = NOW()
                RETURNING triage_id
                """,
                Map.of(
                        "nid", id,
                        "tid", tenantId,
                        "summary", summary,
                        "risk", risk,
                        "status", status),
                UUID.class);

        // Replace pending checklist items (keep resolved items)
        jdbc.update(
                "DELETE FROM notice_document_requirements WHERE notice_id = :nid AND status = 'pending'",
                Map.of("nid", id));

        for (Map<String, Object> item : checklist) {
            String reqName = (String) item.getOrDefault("name", "");
            String reqDesc = (String) item.getOrDefault("description", "");
            boolean required = Boolean.TRUE.equals(item.get("required"));

            jdbc.update(
                    """
                    INSERT INTO notice_document_requirements (notice_id, tenant_id, name, description, is_required, status)
                    VALUES (:nid, :tid, :name, :desc, :required, 'pending')
                    """,
                    Map.of("nid", id, "tid", tenantId, "name", reqName, "desc", reqDesc, "required", required));
        }

        auditService.emit(tenantId, userId, "triage.generated", "notices", id.toString(),
                null, Map.of("triage_id", triageId.toString(), "risk_level", risk), 1);

        log.info("Triage generated: notice_id={} triage_id={} tenant={}", id, triageId, tenantId);

        return Map.of(
                "triage_id", triageId,
                "notice_id", id,
                "status", status,
                "risk_level", risk,
                "summary", summary,
                "checklist_count", checklist.size());
    }

    // ---- GET /v1/notices/{id}/triage ----

    @GetMapping("/notices/{id}/triage")
    @Transactional
    public Map<String, Object> getTriage(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var triageRows = jdbc.queryForList(
                """
                SELECT triage_id, notice_id, summary, risk_level, status, created_at, updated_at
                FROM notice_triage WHERE notice_id = :nid
                """,
                Map.of("nid", id));

        if (triageRows.isEmpty()) {
            return Map.of("status", "not_started", "checklist", List.of());
        }

        Map<String, Object> triage = new HashMap<>(triageRows.get(0));

        List<Map<String, Object>> checklist = jdbc.queryForList(
                """
                SELECT r.requirement_id, r.name, r.description, r.is_required, r.status, r.not_applicable_reason,
                       d.document_id, d.filename AS document_filename, d.mime_type AS document_mime_type
                FROM notice_document_requirements r
                LEFT JOIN documents d ON d.document_id = r.document_id
                WHERE r.notice_id = :nid
                ORDER BY r.is_required DESC, r.name ASC
                """,
                Map.of("nid", id));

        triage.put("checklist", checklist);

        return triage;
    }

    // ---- POST /v1/notices/{id}/checklist/{rid}/attach ----

    @PostMapping("/notices/{id}/checklist/{rid}/attach")
    @Transactional
    public Map<String, Object> attachDocument(
            @PathVariable UUID id,
            @PathVariable UUID rid,
            @RequestBody AttachDocumentRequest request) {

        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Validate requirement exists for this notice
        var reqRows = jdbc.queryForList(
                "SELECT requirement_id FROM notice_document_requirements WHERE requirement_id = :rid AND notice_id = :nid",
                Map.of("rid", rid, "nid", id));
        if (reqRows.isEmpty()) {
            throw new NotFoundException("Requirement not found: " + rid);
        }

        // Validate document exists
        var docRows = jdbc.queryForList(
                "SELECT document_id FROM documents WHERE document_id = :did",
                Map.of("did", request.documentId()));
        if (docRows.isEmpty()) {
            throw new NotFoundException("Document not found: " + request.documentId());
        }

        jdbc.update(
                "UPDATE notice_document_requirements SET status = 'uploaded', document_id = :did WHERE requirement_id = :rid",
                Map.of("rid", rid, "did", request.documentId()));

        refreshTriageStatus(id, tenantId);

        log.info("Document attached to checklist: notice_id={} req_id={} doc_id={}", id, rid, request.documentId());

        return Map.of(
                "requirement_id", rid,
                "notice_id", id,
                "status", "uploaded",
                "document_id", request.documentId());
    }

    // ---- POST /v1/notices/{id}/checklist/{rid}/mark-na ----

    @PostMapping("/notices/{id}/checklist/{rid}/mark-na")
    @Transactional
    public Map<String, Object> markNotApplicable(
            @PathVariable UUID id,
            @PathVariable UUID rid,
            @RequestBody(required = false) MarkNaRequest request) {

        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var reqRows = jdbc.queryForList(
                "SELECT requirement_id FROM notice_document_requirements WHERE requirement_id = :rid AND notice_id = :nid",
                Map.of("rid", rid, "nid", id));
        if (reqRows.isEmpty()) {
            throw new NotFoundException("Requirement not found: " + rid);
        }

        String reason = (request != null && request.reason() != null) ? request.reason() : "";

        jdbc.update(
                "UPDATE notice_document_requirements SET status = 'not_applicable', not_applicable_reason = :reason WHERE requirement_id = :rid",
                Map.of("rid", rid, "reason", reason));

        refreshTriageStatus(id, tenantId);

        log.info("Checklist item marked N/A: notice_id={} req_id={}", id, rid);

        return Map.of(
                "requirement_id", rid,
                "notice_id", id,
                "status", "not_applicable",
                "reason", reason);
    }

    // ---- Helper: refresh triage status ----

    private void refreshTriageStatus(UUID noticeId, String tenantId) {
        Integer pendingRequired = jdbc.queryForObject(
                """
                SELECT COUNT(*)::int FROM notice_document_requirements
                WHERE notice_id = :nid AND is_required = true AND status = 'pending'
                """,
                Map.of("nid", noticeId),
                Integer.class);

        String newStatus = (pendingRequired != null && pendingRequired == 0) ? "ready_to_draft" : "ready_to_gather";

        jdbc.update(
                "UPDATE notice_triage SET status = :status, updated_at = NOW() WHERE notice_id = :nid",
                Map.of("nid", noticeId, "status", newStatus));

        log.info("Triage status refreshed: notice_id={} status={}", noticeId, newStatus);
    }

    // ---- Request records ----

    public record AttachDocumentRequest(UUID documentId) {}

    public record MarkNaRequest(String reason) {}
}
