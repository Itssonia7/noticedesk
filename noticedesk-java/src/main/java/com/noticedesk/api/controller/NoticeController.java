package com.noticedesk.api.controller;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class NoticeController {

    private static final List<String> ALL_STATUSES = List.of(
            "issued", "in_progress", "due", "due_date_over", "reply_submitted",
            "acknowledged", "order_received", "appeal_filed", "closed", "on_hold");

    private static final Set<String> STATUSES_REQUIRING_REASON = Set.of(
            "closed", "on_hold", "reply_submitted");

    private static final Set<String> ALLOWED_UPDATE_FIELDS = Set.of(
            "document_type", "due_date", "issue_date", "hearing_date", "financial_year",
            "assessment_year", "authority", "din_or_rfn", "notice_number", "issue",
            "assigned_to", "ingest_channel", "lifecycle_status");

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final AppProperties properties;

    // ---- GET /v1/notices ----

    @GetMapping("/notices")
    @Transactional
    public Map<String, Object> listNotices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String law,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID registrationId,
            @RequestParam(required = false) String stateCode,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int page_size) {

        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        Map<String, Object> params = new HashMap<>();
        params.put("limit", page_size);
        params.put("offset", (page - 1) * page_size);

        List<String> conditions = new ArrayList<>();
        conditions.add("c.deleted_at IS NULL");

        if (status != null && !status.isBlank()) {
            conditions.add("n.lifecycle_status = :status");
            params.put("status", status);
        }
        if (law != null && !law.isBlank()) {
            conditions.add("n.law = :law");
            params.put("law", law.toUpperCase());
        }
        if (clientId != null) {
            conditions.add("n.client_id = :clientId");
            params.put("clientId", clientId);
        }
        if (registrationId != null) {
            conditions.add("n.registration_id = :registrationId");
            params.put("registrationId", registrationId);
        }
        if (stateCode != null && !stateCode.isBlank()) {
            conditions.add("r.state_code = :stateCode");
            params.put("stateCode", stateCode);
        }
        if (fromDate != null && !fromDate.isBlank()) {
            conditions.add("n.due_date >= :fromDate");
            params.put("fromDate", LocalDate.parse(fromDate));
        }
        if (toDate != null && !toDate.isBlank()) {
            conditions.add("n.due_date <= :toDate");
            params.put("toDate", LocalDate.parse(toDate));
        }

        String whereClause = String.join(" AND ", conditions);

        String countSql = "SELECT COUNT(*)::int FROM notices n"
                + " JOIN clients c ON c.client_id = n.client_id"
                + " JOIN client_registrations r ON r.registration_id = n.registration_id"
                + " WHERE " + whereClause;

        Integer total = jdbc.queryForObject(countSql, params, Integer.class);

        String listSql =
                """
                SELECT n.notice_id, n.law, n.document_type, n.due_date, n.financial_year, n.assessment_year,
                       n.lifecycle_status, n.ingest_channel, n.din_or_rfn, n.raw_extracted_json,
                       c.client_id, c.legal_name AS client_legal_name, c.pan AS client_pan,
                       r.registration_id, r.registration_type, r.identifier_value AS registration_identifier,
                       r.state_code AS registration_state_code, r.state_name AS registration_state_name
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                JOIN client_registrations r ON r.registration_id = n.registration_id
                WHERE \s"""
                + whereClause
                + """
                \sORDER BY n.due_date ASC NULLS LAST, n.notice_id ASC
                LIMIT :limit OFFSET :offset
                """;

        List<Map<String, Object>> rawItems = jdbc.queryForList(listSql, params);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rawItems) {
            Map<String, Object> m = new HashMap<>(r);
            if (m.get("raw_extracted_json") instanceof Map<?, ?> rawMap) {
                m.put("issue", rawMap.get("issue"));
                m.put("assigned_to", rawMap.get("assigned_to"));
            }
            items.add(m);
        }

        return Map.of(
                "notices", items,
                "page", page,
                "page_size", page_size,
                "total", total != null ? total : 0);
    }

    // ---- GET /v1/dashboard/status_counts ----

    @GetMapping("/dashboard/status_counts")
    @Transactional
    public Map<String, Object> statusCounts() {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT n.lifecycle_status, COUNT(*)::int AS count
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                WHERE c.deleted_at IS NULL
                GROUP BY n.lifecycle_status
                """,
                Map.of());

        // Zero-fill all 10 statuses
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : ALL_STATUSES) {
            counts.put(s, 0);
        }
        for (Map<String, Object> row : rows) {
            String s = (String) row.get("lifecycle_status");
            if (counts.containsKey(s)) {
                counts.put(s, row.get("count"));
            }
        }

        return counts;
    }

    // ---- GET /v1/dashboard/today ----

    @GetMapping("/dashboard/today")
    @Transactional
    public Map<String, Object> dashboardToday() {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(7);

        List<Map<String, Object>> items = jdbc.queryForList(
                """
                SELECT n.notice_id, n.law, n.document_type, n.due_date, n.lifecycle_status, n.raw_extracted_json,
                       c.client_id, c.legal_name AS client_legal_name, r.identifier_value AS reg_identifier
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                JOIN client_registrations r ON r.registration_id = n.registration_id
                WHERE c.deleted_at IS NULL
                  AND n.lifecycle_status IN ('issued','in_progress','due','due_date_over')
                  AND n.due_date IS NOT NULL
                  AND n.due_date <= :horizon
                ORDER BY n.due_date ASC
                """,
                Map.of("horizon", horizon));

        List<Map<String, Object>> notices = items.stream().map(HashMap::new).map(m -> (Map<String, Object>) m).toList();
        return Map.of(
                "today", today.toString(),
                "horizon", horizon.toString(),
                "notices", notices,
                "total", notices.size());
    }

    // ---- POST /v1/notices ----

    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createNotice(@RequestBody CreateNoticeRequest request) {
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        if (request.clientId() == null) {
            throw new AppValidationException("clientId is required");
        }
        if (request.registrationId() == null) {
            throw new AppValidationException("registrationId is required");
        }

        // Verify client + registration exist
        var clientRows = jdbc.queryForList(
                "SELECT client_id FROM clients WHERE client_id = :cid AND deleted_at IS NULL",
                Map.of("cid", request.clientId()));
        if (clientRows.isEmpty()) {
            throw new NotFoundException("Client not found: " + request.clientId());
        }

        // Find or create matter
        UUID matterId = findOrCreateMatter(tenantId, request.clientId(), request.registrationId());

        UUID noticeId = jdbc.queryForObject(
                """
                INSERT INTO notices
                    (tenant_id, matter_id, client_id, registration_id, law, document_type,
                     due_date, issue_date, hearing_date, financial_year, assessment_year,
                     authority, din_or_rfn, notice_number, issue, assigned_to, ingest_channel,
                     lifecycle_status)
                VALUES
                    (:tid::uuid, :mid, :cid, :rid, :law, :dtype,
                     :due, :issue_date, :hearing, :fy, :ay,
                     :authority, :din, :num, :issue_text, :assigned::uuid, :channel,
                     'issued')
                RETURNING notice_id
                """,
                Map.ofEntries(
                        Map.entry("tid", tenantId),
                        Map.entry("mid", matterId),
                        Map.entry("cid", request.clientId()),
                        Map.entry("rid", request.registrationId()),
                        Map.entry("law", request.law() != null ? request.law().toUpperCase() : ""),
                        Map.entry("dtype", request.documentType() != null ? request.documentType() : ""),
                        Map.entry("due", request.dueDate()),
                        Map.entry("issue_date", request.issueDate()),
                        Map.entry("hearing", request.hearingDate()),
                        Map.entry("fy", request.financialYear() != null ? request.financialYear() : ""),
                        Map.entry("ay", request.assessmentYear() != null ? request.assessmentYear() : ""),
                        Map.entry("authority", request.authority() != null ? request.authority() : ""),
                        Map.entry("din", request.dinOrRfn() != null ? request.dinOrRfn() : ""),
                        Map.entry("num", request.noticeNumber() != null ? request.noticeNumber() : ""),
                        Map.entry("issue_text", request.issue() != null ? request.issue() : ""),
                        Map.entry("assigned", request.assignedTo()),
                        Map.entry("channel", request.ingestChannel() != null ? request.ingestChannel() : "manual")),
                UUID.class);

        auditService.emit(tenantId, userId, "notice.created", "notices", noticeId.toString(),
                null, Map.of("client_id", request.clientId().toString(), "matter_id", matterId.toString()), 1);

        log.info("Notice created: notice_id={} matter_id={} tenant={}", noticeId, matterId, tenantId);

        return Map.of(
                "notice_id", noticeId,
                "matter_id", matterId,
                "lifecycle_status", "issued");
    }

    // ---- GET /v1/notices/{id} ----

    @GetMapping("/notices/{id}")
    @Transactional
    public Map<String, Object> getNotice(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var rows = jdbc.queryForList(
                """
                SELECT n.notice_id, n.matter_id, n.law, n.document_type, n.notice_number, n.din_or_rfn,
                       n.issue_date, n.due_date, n.receipt_date, n.hearing_date,
                       n.authority, n.financial_year, n.assessment_year,
                       n.issues, n.documents_required, n.demand_amount,
                       n.lifecycle_status, n.ingest_channel,
                       n.pan_gstin_reconciliation_status, n.parse_confidence, n.verification_status,
                       n.raw_extracted_json, n.manual_corrections_json, n.created_at, n.updated_at,
                       c.client_id, c.legal_name AS client_legal_name, c.pan AS client_pan,
                       c.entity_type AS client_entity_type, c.industry AS client_industry,
                       r.registration_id, r.registration_type, r.identifier_value AS registration_identifier,
                       r.state_code AS registration_state_code, r.state_name AS registration_state_name,
                       r.jurisdiction_office AS registration_jurisdiction
                FROM notices n
                JOIN clients c ON c.client_id = n.client_id
                JOIN client_registrations r ON r.registration_id = n.registration_id
                WHERE n.notice_id = :nid AND c.deleted_at IS NULL
                """,
                Map.of("nid", id));

        if (rows.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        Map<String, Object> row = new HashMap<>(rows.get(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = row.get("raw_extracted_json") instanceof Map
                ? (Map<String, Object>) row.get("raw_extracted_json")
                : Map.of();

        Map<String, Object> noticeMap = new HashMap<>(row);
        noticeMap.put("issue", raw.get("issue"));
        noticeMap.put("assigned_to", raw.get("assigned_to"));

        Map<String, Object> clientMap = Map.of(
                "client_id", row.get("client_id").toString(),
                "legal_name", row.get("client_legal_name"),
                "pan", row.get("client_pan"),
                "entity_type", row.get("client_entity_type") != null ? row.get("client_entity_type") : "",
                "industry", row.get("client_industry") != null ? row.get("client_industry") : ""
        );

        Map<String, Object> regMap = Map.of(
                "registration_id", row.get("registration_id").toString(),
                "registration_type", row.get("registration_type"),
                "identifier_value", row.get("registration_identifier"),
                "state_code", row.get("registration_state_code") != null ? row.get("registration_state_code") : "",
                "state_name", row.get("registration_state_name") != null ? row.get("registration_state_name") : "",
                "jurisdiction_office", row.get("registration_jurisdiction") != null ? row.get("registration_jurisdiction") : ""
        );

        return Map.of(
                "notice", noticeMap,
                "client", clientMap,
                "registration", regMap
        );
    }

    // ---- PATCH /v1/notices/{id} ----

    @PatchMapping("/notices/{id}")
    @Transactional
    public Map<String, Object> updateNotice(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {

        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var existing = jdbc.queryForList(
                "SELECT notice_id FROM notices WHERE notice_id = :nid",
                Map.of("nid", id));
        if (existing.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("nid", id);
        List<String> setClauses = new ArrayList<>();

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String field = toSnakeCase(entry.getKey());
            if (ALLOWED_UPDATE_FIELDS.contains(field)) {
                setClauses.add(field + " = :" + field);
                params.put(field, entry.getValue());
            }
        }

        if (setClauses.isEmpty()) {
            throw new AppValidationException("No valid fields to update");
        }

        setClauses.add("updated_at = NOW()");
        String sql = "UPDATE notices SET " + String.join(", ", setClauses) + " WHERE notice_id = :nid";
        jdbc.update(sql, params);

        auditService.emit(tenantId, userId, "notice.updated", "notices", id.toString(),
                existing.get(0), params, 1);

        log.info("Notice updated: notice_id={} tenant={}", id, tenantId);

        return getNotice(id);
    }

    // ---- PATCH /v1/notices/{id}/lifecycle ----

    @PatchMapping("/notices/{id}/lifecycle")
    @Transactional
    public Map<String, Object> lifecycleTransition(
            @PathVariable UUID id,
            @RequestBody LifecycleRequest request) {

        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        if (request.targetStatus() == null || !ALL_STATUSES.contains(request.targetStatus())) {
            throw new AppValidationException(
                    "Invalid target status. Must be one of: " + ALL_STATUSES);
        }

        if (STATUSES_REQUIRING_REASON.contains(request.targetStatus())
                && (request.reason() == null || request.reason().isBlank())) {
            throw new AppValidationException(
                    "reason is required for status: " + request.targetStatus());
        }

        var existing = jdbc.queryForList(
                "SELECT notice_id, lifecycle_status FROM notices WHERE notice_id = :nid",
                Map.of("nid", id));
        if (existing.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        String currentStatus = (String) existing.get(0).get("lifecycle_status");

        if (currentStatus.equals(request.targetStatus())) {
            // No-op
            return Map.of("notice_id", id, "lifecycle_status", currentStatus, "changed", false);
        }

        jdbc.update(
                """
                UPDATE notices SET lifecycle_status = :status, updated_at = NOW()
                WHERE notice_id = :nid
                """,
                Map.of("nid", id, "status", request.targetStatus()));

        auditService.emit(tenantId, userId, "notice.lifecycle_changed", "notices", id.toString(),
                Map.of("lifecycle_status", currentStatus),
                Map.of("lifecycle_status", request.targetStatus(), "reason", request.reason() != null ? request.reason() : ""),
                1);

        log.info("Notice lifecycle changed: notice_id={} {} -> {} tenant={}",
                id, currentStatus, request.targetStatus(), tenantId);

        return Map.of(
                "notice_id", id,
                "lifecycle_status", request.targetStatus(),
                "changed", true);
    }

    // ---- GET /v1/notices/{id}/timeline ----

    @GetMapping("/notices/{id}/timeline")
    @Transactional
    public List<Map<String, Object>> getTimeline(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Verify notice exists
        var noticeRows = jdbc.queryForList(
                "SELECT notice_id FROM notices WHERE notice_id = :nid",
                Map.of("nid", id));
        if (noticeRows.isEmpty()) {
            throw new NotFoundException("Notice not found: " + id);
        }

        List<Map<String, Object>> events = jdbc.queryForList(
                """
                SELECT a.log_id AS audit_id, a.timestamp, a.action_type, a.before_state, a.after_state,
                       a.risk_tier, u.name AS user_name, u.role AS user_role
                FROM audit_logs a
                LEFT JOIN users u ON u.user_id = a.user_id
                WHERE a.entity_type = 'notices' AND a.entity_id = :nid
                ORDER BY a.timestamp DESC
                """,
                Map.of("nid", id.toString()));

        return events.stream().map(HashMap::new).map(m -> (Map<String, Object>) m).toList();
    }

    // ---- Helper: find or create matter ----

    private UUID findOrCreateMatter(String tenantId, UUID clientId, UUID registrationId) {
        var existing = jdbc.queryForList(
                "SELECT matter_id FROM matters WHERE client_id = :cid AND registration_id = :rid AND deleted_at IS NULL LIMIT 1",
                Map.of("cid", clientId, "rid", registrationId));

        if (!existing.isEmpty()) {
            return (UUID) existing.get(0).get("matter_id");
        }

        return jdbc.queryForObject(
                """
                INSERT INTO matters (tenant_id, client_id, registration_id)
                VALUES (:tid::uuid, :cid, :rid)
                RETURNING matter_id
                """,
                Map.of("tid", tenantId, "cid", clientId, "rid", registrationId),
                UUID.class);
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ---- Request records ----

    public record CreateNoticeRequest(
            UUID clientId,
            UUID registrationId,
            String law,
            String documentType,
            LocalDate dueDate,
            LocalDate issueDate,
            LocalDate hearingDate,
            String financialYear,
            String assessmentYear,
            String authority,
            String dinOrRfn,
            String noticeNumber,
            String issue,
            UUID assignedTo,
            String ingestChannel) {}

    public record LifecycleRequest(String targetStatus, String reason) {}
}
