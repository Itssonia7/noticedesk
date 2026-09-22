package com.noticedesk.api.controller;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.exception.AppValidationException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.DocxExportService;
import com.noticedesk.api.security.TenantContextHolder;
import com.noticedesk.api.workflow.WorkflowDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DraftController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final AppProperties properties;
    private final WorkflowDispatcher workflowDispatcher;
    private final DocxExportService docxExportService;

    // ---- POST /v1/notices/{id}/draft ----

    @PostMapping("/notices/{id}/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> generateDraft(
            @PathVariable UUID id,
            @RequestBody(required = false) GenerateDraftRequest request) {
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Find matter_id from notice
        var noticeRows = jdbc.queryForList(
                "SELECT notice_id, matter_id FROM notices WHERE notice_id = :nid",
                Map.of("nid", id));
        if (noticeRows.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        UUID matterId = (UUID) noticeRows.get(0).get("matter_id");

        // Build job payload
        Map<String, Object> job = new HashMap<>();
        job.put("notice_id", id.toString());
        job.put("matter_id", matterId.toString());
        job.put("tenant_id", tenantId);
        job.put("user_id", userId);
        if (request != null) {
            if (request.tone() != null) job.put("tone", request.tone());
            if (request.partnerInstructions() != null) job.put("partner_instructions", request.partnerInstructions());
            job.put("include_cross_registration", Boolean.TRUE.equals(request.includeCrossRegistration()));
        }

        // Commit read transaction context, then call workflow
        Map<String, Object> result = workflowDispatcher.dispatchGenerateDraft(job);

        UUID draftId = UUID.fromString((String) result.get("draftId"));
        int version = (int) result.getOrDefault("version", 1);

        auditService.emit(tenantId, userId, "draft.generated", "drafts", draftId.toString(),
                null, Map.of("notice_id", id.toString(), "version", version), 1);

        log.info("Draft generated: draft_id={} notice_id={} version={} tenant={}", draftId, id, version, tenantId);

        return Map.of(
                "draft_id", draftId,
                "notice_id", id,
                "matter_id", matterId,
                "version", version,
                "citation_summary", result.getOrDefault("citationSummary", Map.of()),
                "sections_kept", result.getOrDefault("sectionsKept", 0));
    }

    // ---- GET /v1/matters/{id}/drafts ----

    @GetMapping("/matters/{id}/drafts")
    @Transactional
    public Map<String, Object> listDrafts(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        List<Map<String, Object>> drafts = jdbc.queryForList(
                """
                SELECT d.draft_id, d.matter_id, d.version, d.status, d.created_at,
                       u.name AS generated_by_name
                FROM drafts d
                LEFT JOIN users u ON u.user_id = d.generated_by
                WHERE d.matter_id = :mid
                ORDER BY d.version DESC
                """,
                Map.of("mid", id));

        return Map.of("drafts", drafts, "total", drafts.size());
    }

    // ---- GET /v1/drafts/{id} ----

    @GetMapping("/drafts/{id}")
    @Transactional
    public Map<String, Object> getDraft(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var draftRows = jdbc.queryForList(
                """
                SELECT d.draft_id, d.matter_id, d.version, d.status, d.sections, d.internal_note,
                       d.edits_log, d.created_at,
                       u.name AS generated_by_name
                FROM drafts d
                LEFT JOIN users u ON u.user_id = d.generated_by
                WHERE d.draft_id = :did
                """,
                Map.of("did", id));

        if (draftRows.isEmpty()) {
            throw new NotFoundException("Draft not found: " + id);
        }

        Map<String, Object> draft = new HashMap<>(draftRows.get(0));

        List<Map<String, Object>> citations = jdbc.queryForList(
                """
                SELECT citation_id, section_num, text, source_type, source_ref, confidence, created_at
                FROM draft_citations
                WHERE draft_id = :did
                ORDER BY section_num, citation_id
                """,
                Map.of("did", id));

        draft.put("citations", citations);

        return draft;
    }

    // ---- PATCH /v1/drafts/{id}/section ----

    @PatchMapping("/drafts/{id}/section")
    @Transactional
    public Map<String, Object> editSection(
            @PathVariable UUID id,
            @RequestBody EditSectionRequest request) {

        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        if (request.sectionNum() == null) {
            throw new AppValidationException("sectionNum is required");
        }
        if (request.bodyHtml() == null) {
            throw new AppValidationException("bodyHtml is required");
        }

        // Load existing draft
        var draftRows = jdbc.queryForList(
                """
                SELECT draft_id, matter_id, version, sections, edits_log, internal_note, status, generated_by
                FROM drafts WHERE draft_id = :did
                """,
                Map.of("did", id));

        if (draftRows.isEmpty()) {
            throw new NotFoundException("Draft not found: " + id);
        }

        Map<String, Object> existing = draftRows.get(0);
        UUID matterId = (UUID) existing.get("matter_id");

        // Parse existing sections (stored as JSONB)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) existing.getOrDefault("sections", List.of());

        // Update the target section
        boolean found = false;
        List<Map<String, Object>> updatedSections = new ArrayList<>();
        for (Map<String, Object> section : sections) {
            Map<String, Object> updatedSection = new HashMap<>(section);
            Object snum = section.get("num");
            if (snum != null && snum.toString().equals(request.sectionNum().toString())) {
                updatedSection.put("body_html", request.bodyHtml());
                found = true;
            }
            updatedSections.add(updatedSection);
        }

        if (!found) {
            throw new NotFoundException("Section " + request.sectionNum() + " not found in draft");
        }

        // Compute next version
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM drafts WHERE matter_id = :mid",
                Map.of("mid", matterId),
                Integer.class);

        // Build edits_log entry
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> editsLog = (List<Map<String, Object>>) existing.getOrDefault("edits_log", new ArrayList<>());
        List<Map<String, Object>> newEditsLog = new ArrayList<>(editsLog);
        newEditsLog.add(Map.of(
                "section_num", request.sectionNum(),
                "edited_by", userId,
                "edited_at", LocalDateTime.now().toString(),
                "note", request.note() != null ? request.note() : ""));

        // Insert new draft version
        UUID newDraftId = jdbc.queryForObject(
                """
                INSERT INTO drafts (matter_id, tenant_id, version, status, sections, edits_log, internal_note, generated_by)
                VALUES (:mid, :tid::uuid, :version, :status, :sections::jsonb, :edits::jsonb, :note, :gen_by::uuid)
                RETURNING draft_id
                """,
                Map.of(
                        "mid", matterId,
                        "tid", tenantId,
                        "version", nextVersion,
                        "status", existing.get("status"),
                        "sections", toJsonString(updatedSections),
                        "edits", toJsonString(newEditsLog),
                        "note", existing.getOrDefault("internal_note", ""),
                        "gen_by", existing.get("generated_by")),
                UUID.class);

        // Copy citations from old draft to new draft
        jdbc.update(
                """
                INSERT INTO draft_citations (draft_id, section_num, text, source_type, source_ref, confidence)
                SELECT :new_did, section_num, text, source_type, source_ref, confidence
                FROM draft_citations WHERE draft_id = :old_did
                """,
                Map.of("new_did", newDraftId, "old_did", id));

        auditService.emit(tenantId, userId, "draft.section_edited", "drafts", newDraftId.toString(),
                Map.of("parent_draft_id", id.toString()),
                Map.of("section_num", request.sectionNum(), "version", nextVersion), 1);

        log.info("Draft section edited: old_draft_id={} new_draft_id={} section={} tenant={}",
                id, newDraftId, request.sectionNum(), tenantId);

        return Map.of(
                "draft_id", newDraftId,
                "version", nextVersion,
                "matter_id", matterId,
                "parent_draft_id", id);
    }

    // ---- GET /v1/drafts/{id}/export ----

    @GetMapping("/drafts/{id}/export")
    @Transactional
    public ResponseEntity<byte[]> exportDraft(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "reply") String mode) throws Exception {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Load draft + matter + notice + client + registration + tenant info
        var draftRows = jdbc.queryForList(
                """
                SELECT d.draft_id, d.matter_id, d.version, d.sections, d.internal_note, d.status
                FROM drafts d WHERE d.draft_id = :did
                """,
                Map.of("did", id));

        if (draftRows.isEmpty()) {
            throw new NotFoundException("Draft not found: " + id);
        }

        Map<String, Object> draft = draftRows.get(0);
        UUID matterId = (UUID) draft.get("matter_id");

        var matterRows = jdbc.queryForList(
                """
                SELECT m.matter_id, m.client_id, m.registration_id,
                       n.notice_id, n.law, n.document_type, n.din_or_rfn, n.due_date,
                       n.financial_year, n.assessment_year, n.authority,
                       c.legal_name AS client_legal_name, c.pan AS client_pan,
                       r.identifier_value AS registration_identifier,
                       r.registration_type, r.state_name AS registration_state_name
                FROM matters m
                JOIN notices n ON n.matter_id = m.matter_id
                JOIN clients c ON c.client_id = m.client_id
                JOIN client_registrations r ON r.registration_id = m.registration_id
                WHERE m.matter_id = :mid
                LIMIT 1
                """,
                Map.of("mid", matterId));

        if (matterRows.isEmpty()) {
            throw new NotFoundException("Matter context not found for draft: " + id);
        }

        Map<String, Object> context = new HashMap<>(matterRows.get(0));
        context.put("draft_version", draft.get("version"));
        context.put("tenant_id", tenantId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) draft.getOrDefault("sections", List.of());
        String internalNote = (String) draft.getOrDefault("internal_note", "");

        String fyOrAy = context.get("financial_year") != null
                ? "FY " + context.get("financial_year")
                : (context.get("assessment_year") != null ? "AY " + context.get("assessment_year") : "—");
        DocxExportService.CoverSheetData cover = new DocxExportService.CoverSheetData(
                properties.getEnvironment(),
                (String) context.get("client_legal_name"),
                (String) context.get("client_pan"),
                (String) context.get("registration_type"),
                (String) context.get("registration_identifier"),
                (String) context.get("document_type"),
                fyOrAy,
                (String) context.get("authority"),
                context.get("due_date") != null ? context.get("due_date").toString() : null
        );
        String exportMode = List.of("filing", "client", "internal").contains(mode) ? mode : "reply";
        byte[] docxBytes = docxExportService.renderDraftDocx(exportMode, cover, sections, internalNote);

        String filename = String.format("draft_%s_v%s.docx", id, draft.get("version"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());

        log.info("Draft exported: draft_id={} tenant={}", id, tenantId);

        return new ResponseEntity<>(docxBytes, headers, HttpStatus.OK);
    }

    // ---- Helpers ----

    private String toJsonString(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---- Request records ----

    public record EditSectionRequest(Integer sectionNum, String bodyHtml, String note) {}

    public record GenerateDraftRequest(String tone, String partnerInstructions, Boolean includeCrossRegistration) {}
}
