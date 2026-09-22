package com.noticedesk.api.controller;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.exception.ForbiddenException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
public class ClientController {

    private static final Set<String> ALLOWED_UPDATE_FIELDS = Set.of(
            "legal_name", "trade_name", "entity_type", "industry", "email", "phone");

    private static final String[] OPEN_STATES = {
            "issued", "in_progress", "due", "due_date_over", "reply_submitted",
            "acknowledged", "order_received", "appeal_filed", "on_hold"};

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final AppProperties properties;

    @GetMapping("/clients")
    @Transactional
    public List<Map<String, Object>> listClients() {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT
                    c.client_id, c.pan, c.legal_name, c.trade_name, c.entity_type, c.industry,
                    COALESCE(gst.gst_count, 0) AS gst_count,
                    COALESCE(gst.state_codes, '{}') AS gst_state_codes,
                    COALESCE(n.active_it_count, 0) AS active_it_count,
                    COALESCE(n.active_gst_count, 0) AS active_gst_count,
                    n.earliest_open_due_date
                FROM clients c
                LEFT JOIN (
                    SELECT client_id, COUNT(*)::int AS gst_count,
                           ARRAY_AGG(state_code ORDER BY state_code) AS state_codes
                    FROM client_registrations
                    WHERE registration_type = 'GST' AND registration_status = 'active'
                    GROUP BY client_id
                ) gst ON gst.client_id = c.client_id
                LEFT JOIN (
                    SELECT client_id,
                           COUNT(*) FILTER (WHERE law='IT')::int AS active_it_count,
                           COUNT(*) FILTER (WHERE law='GST')::int AS active_gst_count,
                           MIN(due_date) FILTER (WHERE due_date IS NOT NULL) AS earliest_open_due_date
                    FROM notices
                    WHERE lifecycle_status = ANY(:open_states)
                    GROUP BY client_id
                ) n ON n.client_id = c.client_id
                WHERE c.deleted_at IS NULL
                ORDER BY c.legal_name ASC
                """,
                Map.of("open_states", OPEN_STATES));

        return rows.stream()
                .map(HashMap::new)
                .peek(m -> {
                    Object arr = m.get("gst_state_codes");
                    if (arr instanceof java.sql.Array) {
                        try { m.put("gst_state_codes", ((java.sql.Array) arr).getArray()); }
                        catch (Exception e) { m.put("gst_state_codes", new Object[0]); }
                    }
                })
                .map(m -> (Map<String, Object>) m)
                .toList();
    }

    @GetMapping("/clients/{id}")
    @Transactional
    public Map<String, Object> getClient(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var clientRows = jdbc.queryForList(
                """
                SELECT c.client_id, c.pan, c.legal_name, c.trade_name, c.entity_type, c.industry,
                       c.email, c.phone, c.created_at, c.updated_at
                FROM clients c
                WHERE c.client_id = :cid AND c.deleted_at IS NULL
                """,
                Map.of("cid", id));

        if (clientRows.isEmpty()) {
            throw new NotFoundException("Client not found: " + id);
        }

        Map<String, Object> client = new HashMap<>(clientRows.get(0));

        List<Map<String, Object>> registrations = jdbc.queryForList(
                """
                SELECT r.registration_id, r.registration_type, r.identifier_value, r.state_code,
                       r.state_name, r.jurisdiction_office, r.registration_status, r.created_at,
                       COALESCE(n.active_notice_count, 0) AS active_notice_count,
                       n.earliest_open_due_date,
                       'manual' AS sync_method,
                       NULL AS last_synced_at
                FROM client_registrations r
                LEFT JOIN (
                    SELECT registration_id,
                           COUNT(*)::int AS active_notice_count,
                           MIN(due_date) FILTER (WHERE due_date IS NOT NULL) AS earliest_open_due_date
                    FROM notices
                    WHERE lifecycle_status = ANY(:open_states)
                    GROUP BY registration_id
                ) n ON n.registration_id = r.registration_id
                WHERE r.client_id = :cid
                ORDER BY r.registration_type, r.state_code
                """,
                Map.of("cid", id, "open_states", OPEN_STATES));

        client.put("registrations", registrations);

        return client;
    }

    @PatchMapping("/clients/{id}")
    @Transactional
    public Map<String, Object> updateClient(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {

        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // Verify client exists
        var existing = jdbc.queryForList(
                "SELECT client_id, pan, legal_name, trade_name, entity_type, industry, email, phone FROM clients WHERE client_id = :cid AND deleted_at IS NULL",
                Map.of("cid", id));
        if (existing.isEmpty()) {
            throw new NotFoundException("Client not found: " + id);
        }

        // Reject PAN updates
        if (body.containsKey("pan")) {
            throw new com.noticedesk.api.exception.AppValidationException("PAN cannot be updated after creation");
        }

        // Build dynamic SET clause from allowed fields only
        Map<String, Object> params = new HashMap<>();
        params.put("cid", id);
        List<String> setClauses = new ArrayList<>();

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String field = toSnakeCase(entry.getKey());
            if (ALLOWED_UPDATE_FIELDS.contains(field)) {
                setClauses.add(field + " = :" + field);
                params.put(field, entry.getValue());
            }
        }

        if (setClauses.isEmpty()) {
            throw new com.noticedesk.api.exception.AppValidationException("No valid fields to update");
        }

        setClauses.add("updated_at = NOW()");
        String sql = "UPDATE clients SET " + String.join(", ", setClauses) + " WHERE client_id = :cid";

        jdbc.update(sql, params);

        auditService.emit(tenantId, userId, "client.updated", "clients", id.toString(),
                existing.get(0), params, 1);

        log.info("Client updated: client_id={} tenant={}", id, tenantId);

        return getClient(id);
    }

    @DeleteMapping("/clients/{id}")
    @Transactional
    public Map<String, Object> deleteClient(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        // RBAC: partner or managing_partner only
        String role = jdbc.queryForObject(
                "SELECT role FROM users WHERE user_id = :uid",
                Map.of("uid", userId),
                String.class);

        if (role == null || (!role.equals("partner") && !role.equals("managing_partner"))) {
            throw new ForbiddenException("Only partners may delete clients");
        }

        var existing = jdbc.queryForList(
                "SELECT client_id, pan, legal_name FROM clients WHERE client_id = :cid AND deleted_at IS NULL",
                Map.of("cid", id));
        if (existing.isEmpty()) {
            throw new NotFoundException("Client not found: " + id);
        }

        jdbc.update(
                "UPDATE clients SET deleted_at = NOW() WHERE client_id = :cid",
                Map.of("cid", id));

        auditService.emit(tenantId, userId, "client.deleted", "clients", id.toString(),
                existing.get(0), Map.of("deleted", true), 2);

        log.info("Client soft-deleted: client_id={} tenant={}", id, tenantId);

        return Map.of("client_id", id, "status", "deleted");
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
