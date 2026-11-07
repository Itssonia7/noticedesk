package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.IdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Rule-based (no LLM) routing agent. Takes parsed document JSON and routes it
 * to the correct client + registration, creating or matching a matter and
 * creating the notice row.
 *
 * Five-step procedure:
 *   A. Extract canonical PAN from identifiers in the parsed JSON.
 *   B. Resolve client by canonical PAN.
 *   C. Resolve registration (GST by GSTIN, IT by PAN).
 *   D. Find-or-create matter.
 *   E. Create notice row, update inbox, emit audit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NoticeRoutingAgent {

    private final IdentityService identityService;
    private final AuditService    auditService;
    private final ObjectMapper    objectMapper;

    // ---- Public data types -----------------------------------------------

    public record RoutingInput(
            UUID                tenantId,
            UUID                inboxId,
            Map<String, Object> parsedJson) {}

    public record RoutingResult(
            UUID   matterId,
            UUID   noticeId,
            String status,
            String error) {}

    // ---- Public API ------------------------------------------------------

    public RoutingResult route(RoutingInput input, NamedParameterJdbcTemplate jdbc) {
        Map<String, Object> parsed = input.parsedJson();

        // ---- Step A: canonical PAN ----------------------------------------
        String canonicalPan = resolveCanonicalPan(parsed);
        if (canonicalPan == null) {
            String err = "no valid PAN or GSTIN extracted from parsed document";
            updateInboxStatus(jdbc, input.inboxId(), "no_identifier_found", err, null);
            log.warn("routing blocked inbox_id={} reason={}", input.inboxId(), err);
            return new RoutingResult(null, null, "routing_blocked", err);
        }

        // ---- Step B: client lookup ----------------------------------------
        List<Map<String, Object>> clients = jdbc.queryForList(
                "SELECT client_id, legal_name FROM clients " +
                "WHERE tenant_id = CAST(:tid AS UUID) AND pan = :pan AND deleted_at IS NULL",
                Map.of("tid", input.tenantId().toString(), "pan", canonicalPan));

        if (clients.isEmpty()) {
            String err = "client not found for PAN: " + canonicalPan;
            updateInboxStatus(jdbc, input.inboxId(), "client_not_found", err, null);
            log.warn("routing blocked inbox_id={} reason={}", input.inboxId(), err);
            return new RoutingResult(null, null, "routing_blocked", err);
        }
        if (clients.size() > 1) {
            String err = "multiple clients found for PAN: " + canonicalPan;
            updateInboxStatus(jdbc, input.inboxId(), "manual_assignment", err, null);
            return new RoutingResult(null, null, "routing_blocked", err);
        }

        UUID   clientId = (UUID) clients.get(0).get("client_id");

        // ---- Step C: registration lookup ----------------------------------
        String law = parsed.get("law") instanceof String s ? s : null;
        if (!"GST".equals(law) && !"IT".equals(law)) {
            String err = "parsed law is null or unknown: " + law;
            updateInboxStatus(jdbc, input.inboxId(), "manual_assignment", err, null);
            return new RoutingResult(null, null, "routing_blocked", err);
        }

        UUID registrationId;
        if ("IT".equals(law)) {
            List<Map<String, Object>> regs = jdbc.queryForList(
                    "SELECT registration_id FROM client_registrations " +
                    "WHERE tenant_id = CAST(:tid AS UUID) AND client_id = CAST(:cid AS UUID) AND registration_type = 'IT' LIMIT 1",
                    Map.of("tid", input.tenantId().toString(), "cid", clientId.toString()));
            if (regs.isEmpty()) {
                String err = "client has no IT registration; expected exactly one";
                updateInboxStatus(jdbc, input.inboxId(), "manual_assignment", err, null);
                return new RoutingResult(null, null, "routing_blocked", err);
            }
            registrationId = (UUID) regs.get(0).get("registration_id");
        } else {
            String gstin = getFirstGstin(parsed);
            if (gstin == null) {
                String err = "no GSTIN extracted for GST law notice";
                updateInboxStatus(jdbc, input.inboxId(), "no_identifier_found", err, null);
                return new RoutingResult(null, null, "routing_blocked", err);
            }
            List<Map<String, Object>> regs = jdbc.queryForList(
                    "SELECT registration_id FROM client_registrations " +
                    "WHERE tenant_id = CAST(:tid AS UUID) AND registration_type = 'GST' AND identifier_value = :iv LIMIT 1",
                    Map.of("tid", input.tenantId().toString(), "iv", gstin));
            if (regs.isEmpty()) {
                String err = "GSTIN not on file: " + gstin;
                updateInboxStatus(jdbc, input.inboxId(), "new_gst_registration_detected", err, null);
                return new RoutingResult(null, null, "routing_blocked", err);
            }
            registrationId = (UUID) regs.get(0).get("registration_id");
        }

        // ---- Step D: find-or-create matter --------------------------------
        UUID matterId = findOrCreateMatter(jdbc, input.tenantId(), clientId, registrationId,
                law,
                (String) parsed.get("financial_year"),
                (String) parsed.get("assessment_year"));

        // ---- Step E: create notice, update inbox, emit audit --------------
        String ingestChannel = queryIngestChannel(jdbc, input.inboxId());
        UUID noticeId = createNotice(jdbc, input.tenantId(), matterId, clientId,
                registrationId, law, parsed, input.inboxId(), ingestChannel);

        updateInboxStatus(jdbc, input.inboxId(), "routed", null, noticeId);

        auditService.emit(
                input.tenantId().toString(), null, "notice.routed",
                "documents_inbox", input.inboxId().toString(),
                null,
                Map.of("matter_id", matterId.toString(),
                        "notice_id", noticeId.toString(),
                        "canonical_pan", canonicalPan,
                        "client_id", clientId.toString(),
                        "law", law),
                1);

        log.info("notice routed inbox_id={} matter_id={} notice_id={} law={}",
                input.inboxId(), matterId, noticeId, law);
        return new RoutingResult(matterId, noticeId, "routed", null);
    }

    // ---- Step A: resolve canonical PAN ------------------------------------

    @SuppressWarnings("unchecked")
    private String resolveCanonicalPan(Map<String, Object> parsed) {
        List<Map<String, Object>> pans    = toMapList(parsed.get("pans_extracted"));
        List<Map<String, Object>> gstins  = toMapList(parsed.get("gstins_extracted"));

        // Cross-check: if both PAN and GSTIN exist, their PAN portions must agree.
        if (!pans.isEmpty() && !gstins.isEmpty()) {
            String panVal   = (String) pans.get(0).get("value");
            String gstinVal = (String) gstins.get(0).get("value");
            String gstinPan = extractPanFromGstin(gstinVal);
            if (panVal != null && !panVal.equals(gstinPan)) {
                log.warn("PAN-GSTIN mismatch pan={} gstin_pan_portion={}", panVal, gstinPan);
                return null; // routing_blocked: pan_gstin_mismatch
            }
        }

        // Prefer GSTIN-derived PAN for GST notices; fall back to explicit PAN.
        if (!gstins.isEmpty()) {
            String gstinVal = (String) gstins.get(0).get("value");
            if (gstinVal != null && identityService.validateGstinFormat(gstinVal)) {
                return extractPanFromGstin(gstinVal);
            }
        }
        if (!pans.isEmpty()) {
            String panVal = (String) pans.get(0).get("value");
            if (panVal != null && identityService.validatePanFormat(panVal)) {
                return panVal;
            }
        }
        return null;
    }

    /** Extract PAN from GSTIN positions 2–12 (0-indexed substring). */
    private String extractPanFromGstin(String gstin) {
        if (gstin == null || gstin.length() < 12) return null;
        return gstin.substring(2, 12);
    }

    @SuppressWarnings("unchecked")
    private String getFirstGstin(Map<String, Object> parsed) {
        List<Map<String, Object>> gstins = toMapList(parsed.get("gstins_extracted"));
        return gstins.isEmpty() ? null : (String) gstins.get(0).get("value");
    }

    // ---- Step D: find-or-create matter ------------------------------------

    private UUID findOrCreateMatter(
            NamedParameterJdbcTemplate jdbc,
            UUID tenantId, UUID clientId, UUID registrationId,
            String law, String financialYear, String assessmentYear) {

        Map<String, Object> params = new HashMap<>();
        params.put("tid", tenantId.toString());
        params.put("cid", clientId.toString());
        params.put("rid", registrationId.toString());
        params.put("law", law);
        params.put("fy", financialYear);
        params.put("ay", assessmentYear);

        // IS NOT DISTINCT FROM handles NULL-equality for nullable FY/AY fields.
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT matter_id FROM matters " +
                "WHERE tenant_id = CAST(:tid AS UUID) AND client_id = CAST(:cid AS UUID) AND registration_id = CAST(:rid AS UUID) " +
                "  AND law = :law " +
                "  AND financial_year  IS NOT DISTINCT FROM :fy " +
                "  AND assessment_year IS NOT DISTINCT FROM :ay " +
                "LIMIT 1",
                params);

        if (!existing.isEmpty()) {
            return (UUID) existing.get(0).get("matter_id");
        }

        UUID matterId = jdbc.queryForObject(
                "INSERT INTO matters (tenant_id, client_id, registration_id, law, financial_year, assessment_year) " +
                "VALUES (CAST(:tid AS UUID), CAST(:cid AS UUID), CAST(:rid AS UUID), :law, :fy, :ay) RETURNING matter_id",
                params,
                UUID.class);

        log.info("matter created matter_id={} law={} fy={}", matterId, law, financialYear);
        return matterId;
    }

    // ---- Step E: create notice row ----------------------------------------

    private UUID createNotice(
            NamedParameterJdbcTemplate jdbc,
            UUID tenantId, UUID matterId, UUID clientId, UUID registrationId,
            String law, Map<String, Object> parsed,
            UUID inboxId, String ingestChannel) {

        String issuesJson;
        String docsJson;
        String rawJson;
        try {
            issuesJson = objectMapper.writeValueAsString(
                    parsed.getOrDefault("issues", List.of()));
            docsJson = objectMapper.writeValueAsString(
                    parsed.getOrDefault("documents_required", List.of()));
            rawJson = objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            issuesJson = "[]";
            docsJson   = "[]";
            rawJson    = "{}";
        }

        // Sanitize ingest_channel to match DB constraint: web_upload, mobile_capture, email, gst_portal_gsp, it_portal_aa, whatsapp
        String sanitizedChannel = "web_upload";
        if (ingestChannel != null) {
            String icLower = ingestChannel.toLowerCase().trim();
            if (List.of("web_upload", "mobile_capture", "email", "gst_portal_gsp", "it_portal_aa", "whatsapp").contains(icLower)) {
                sanitizedChannel = icLower;
            }
        }

        // Sanitize date strings: empty string "" -> null
        String issueDate = sanitizeDate(parsed.get("issue_date"));
        String receiptDate = sanitizeDate(parsed.get("receipt_date"));
        String dueDate = sanitizeDate(parsed.get("due_date"));
        String hearingDate = sanitizeDate(parsed.get("hearing_date"));

        // Sanitize demand_amount to Number/BigDecimal or null
        Object rawDemand = parsed.get("demand_amount");
        Number demandNum = parseDemandAmount(rawDemand);

        Map<String, Object> params = new HashMap<>();
        params.put("tid",          tenantId.toString());
        params.put("mid",          matterId.toString());
        params.put("cid",          clientId.toString());
        params.put("rid",          registrationId.toString());
        params.put("law",          law);
        params.put("doc_type",     parsed.get("document_type"));
        params.put("notice_number", parsed.get("notice_number"));
        params.put("din",          parsed.get("din_or_rfn"));
        params.put("issue_date",   issueDate);
        params.put("receipt_date", receiptDate);
        params.put("due_date",     dueDate);
        params.put("authority",    parsed.get("authority"));
        params.put("fy",           parsed.get("financial_year"));
        params.put("ay",           parsed.get("assessment_year"));
        params.put("issues",       issuesJson);
        params.put("docs",         docsJson);
        params.put("hearing_date", hearingDate);
        params.put("demand_amount", demandNum);
        params.put("ingest_channel", sanitizedChannel);
        params.put("inbox_id",     inboxId.toString());
        params.put("confidence",   parsed.get("parse_confidence"));
        params.put("raw",          rawJson);

        return jdbc.queryForObject(
                """
                INSERT INTO notices (
                    tenant_id, matter_id, client_id, registration_id, law,
                    document_type, notice_number, din_or_rfn,
                    issue_date, receipt_date, due_date, authority,
                    financial_year, assessment_year,
                    issues, documents_required, hearing_date,
                    demand_amount, ingest_channel, source_inbox_id,
                    parse_confidence, pan_gstin_reconciliation_status,
                    raw_extracted_json
                ) VALUES (
                    CAST(:tid AS UUID), CAST(:mid AS UUID), CAST(:cid AS UUID), CAST(:rid AS UUID), :law,
                    :doc_type, :notice_number, :din,
                    CAST(:issue_date AS DATE), CAST(:receipt_date AS DATE),
                    CAST(:due_date AS DATE), :authority,
                    :fy, :ay,
                    CAST(:issues AS JSONB), CAST(:docs AS JSONB),
                    CAST(:hearing_date AS DATE),
                    :demand_amount, :ingest_channel, CAST(:inbox_id AS UUID),
                    :confidence, 'reconciled',
                    CAST(:raw AS JSONB)
                ) RETURNING notice_id
                """,
                params,
                UUID.class);
    }

    // ---- Helpers ----------------------------------------------------------

    private void updateInboxStatus(
            NamedParameterJdbcTemplate jdbc,
            UUID inboxId, String status, String error, UUID noticeId) {

        Map<String, Object> p = new HashMap<>();
        p.put("id",        inboxId.toString());
        p.put("status",    status);
        p.put("error",     error != null ? "{\"error\":\"" + error + "\"}" : null);
        p.put("notice_id", noticeId != null ? noticeId.toString() : null);

        jdbc.update(
                "UPDATE documents_inbox " +
                "SET routing_status = :status, routing_anomaly_details = CAST(:error AS JSONB), " +
                "    parsed_to_notice_id = COALESCE(CAST(:notice_id AS UUID), parsed_to_notice_id) " +
                "WHERE inbox_id = CAST(:id AS UUID)",
                p);
    }

    private String queryIngestChannel(NamedParameterJdbcTemplate jdbc, UUID inboxId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ingest_channel FROM documents_inbox WHERE inbox_id = CAST(:id AS UUID)",
                Map.of("id", inboxId.toString()));
        return rows.isEmpty() ? "unknown" : (String) rows.get(0).get("ingest_channel");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    private String sanitizeDate(Object rawDate) {
        if (rawDate instanceof String s) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("null")) {
                return trimmed;
            }
        }
        return null;
    }

    private Number parseDemandAmount(Object rawDemand) {
        if (rawDemand instanceof Number num) {
            return num;
        }
        if (rawDemand instanceof String str) {
            try {
                String cleanStr = str.replaceAll("[^0-9.]", "");
                if (!cleanStr.isEmpty()) {
                    return Double.parseDouble(cleanStr);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
