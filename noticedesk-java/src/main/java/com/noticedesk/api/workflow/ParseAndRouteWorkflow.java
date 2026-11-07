package com.noticedesk.api.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.agent.NoticeRoutingAgent;
import com.noticedesk.api.security.AuthClaims;
import com.noticedesk.api.security.TenantContextHolder;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.rag.RagStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parse-and-route workflow.
 *
 * Triggered after OCR completes. Reads the OCR text, calls the document
 * parsing agent to extract structured fields, persists the parsed JSON, and
 * then runs the notice routing agent to create or match matters + notices.
 *
 * Two separate transactions mirror the Python pattern:
 *   Tx1 – fetch inbox row, flip parse_status = 'in_progress'
 *   Tx2 – persist parsed JSON, run routing, commit everything
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseAndRouteWorkflow {

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentParsingAgent documentParsingAgent;
    private final NoticeRoutingAgent noticeRoutingAgent;
    private final RagStoreService ragStoreService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ---- Public API -------------------------------------------------------

    public void run(UUID inboxId, UUID tenantId) {
        String tid = tenantId.toString();
        log.info("parse_route.start inbox_id={} tenant_id={}", inboxId, tid);

        // Set thread-local tenant context for @Transactional set_config calls
        TenantContextHolder.set(new AuthClaims(null, tid, null));
        try {
            Map<String, Object> row = fetchInboxRow(tid, inboxId);
            if (row == null) {
                log.warn("parse_route.inbox_missing inbox_id={}", inboxId);
                return;
            }
            String ocrStatus = (String) row.get("ocr_status");
            String routingStatus = (String) row.get("routing_status");
            if (!"completed".equals(ocrStatus)) {
                log.warn("parse_route.ocr_not_done inbox_id={} ocr_status={}", inboxId, ocrStatus);
                return;
            }
            if (!"pending".equals(routingStatus)) {
                log.info("parse_route.already_routed inbox_id={} routing_status={}", inboxId, routingStatus);
                return;
            }

            markParseInProgress(tid, inboxId);

            // Build parse input
            DocumentParsingAgent.ParseInput parseInput = new DocumentParsingAgent.ParseInput(
                    inboxId.toString(),
                    (String) row.getOrDefault("original_filename", ""),
                    (String) row.getOrDefault("ingest_channel", "unknown"),
                    (String) row.getOrDefault("ocr_text", ""),
                    (String) row.get("ocr_provider_used"),
                    row.get("page_count") instanceof Number n ? n.intValue() : null
            );

            DocumentParsingAgent.ParsedDocument parsed;
            try {
                parsed = documentParsingAgent.parseDocument(parseInput);
            } catch (Exception e) {
                log.error("parse_route.parse_failed inbox_id={} error={}", inboxId, e.getMessage());
                markParseFailed(tid, inboxId, e.getMessage());
                return;
            }

            // Persist parsed JSON and run routing in a single transaction
            persistParsedAndRoute(tid, inboxId, parsed, (String) row.get("ingest_channel"));
        } finally {
            TenantContextHolder.clear();
        }
    }

    // ---- Private transactional steps --------------------------------------

    @Transactional
    public Map<String, Object> fetchInboxRow(String tenantId, UUID inboxId) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", Map.of("tid", tenantId), String.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT ocr_status, ocr_text, ocr_provider_used, page_count,
                       original_filename, ingest_channel, routing_status
                FROM documents_inbox
                WHERE inbox_id = CAST(:id AS UUID)
                """, Map.of("id", inboxId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void markParseInProgress(String tenantId, UUID inboxId) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", Map.of("tid", tenantId), String.class);
        jdbc.update("UPDATE documents_inbox SET parse_status = 'in_progress' WHERE inbox_id = CAST(:id AS UUID)",
                Map.of("id", inboxId.toString()));
    }

    @Transactional
    public void markParseFailed(String tenantId, UUID inboxId, String error) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", Map.of("tid", tenantId), String.class);
        jdbc.update("UPDATE documents_inbox SET parse_status = 'failed' WHERE inbox_id = CAST(:id AS UUID)",
                Map.of("id", inboxId.toString()));
        auditService.emit(tenantId, null, "document.parsing.failed",
                "documents_inbox", inboxId.toString(), Map.of("error", error));
    }

    @Transactional
    public void persistParsedAndRoute(String tenantId, UUID inboxId,
                                      DocumentParsingAgent.ParsedDocument parsed,
                                      String ingestChannel) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", Map.of("tid", tenantId), String.class);
        try {
            String parsedJson = objectMapper.writeValueAsString(parsed.payload());
            jdbc.update("""
                    UPDATE documents_inbox
                    SET raw_parsed_json = CAST(:parsed AS JSONB),
                        parse_status    = 'completed'
                    WHERE inbox_id = CAST(:id AS UUID)
                    """, Map.of("id", inboxId.toString(), "parsed", parsedJson));
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist parsed JSON: " + e.getMessage(), e);
        }

        auditService.emit(tenantId, null, "document.parsing.completed",
                "documents_inbox", inboxId.toString(), Map.of(
                        "document_type", String.valueOf(parsed.payload().getOrDefault("document_type", "")),
                        "law", String.valueOf(parsed.payload().getOrDefault("law", "")),
                        "parse_confidence", parsed.payload().getOrDefault("parse_confidence", 0.0),
                        "model", parsed.model(),
                        "prompt_version", parsed.promptVersion(),
                        "provider", parsed.providerName()
                ));

        NoticeRoutingAgent.RoutingInput routingInput = new NoticeRoutingAgent.RoutingInput(
                UUID.fromString(tenantId),
                inboxId,
                parsed.payload()
        );
        NoticeRoutingAgent.RoutingResult result = noticeRoutingAgent.route(routingInput, jdbc);
        log.info("parse_route.routing_done inbox_id={} status={}", inboxId, result.status());

        if (result != null && result.matterId() != null && "routed".equals(result.status())) {
            try {
                Map<String, Object> inboxRow = fetchInboxRow(tenantId, inboxId);
                if (inboxRow != null) {
                    String ocrText = (String) inboxRow.get("ocr_text");
                    String filename = (String) inboxRow.get("original_filename");
                    String docType = parsed.payload() != null && parsed.payload().get("document_type") != null
                            ? parsed.payload().get("document_type").toString()
                            : "NOTICE";
                    ragStoreService.indexEvidenceDocument(UUID.fromString(tenantId), result.matterId(), inboxId, filename, ocrText, docType);
                    log.info("Indexed evidence document for matter_id={} inbox_id={}", result.matterId(), inboxId);
                }
            } catch (Exception e) {
                log.warn("Auto-indexing evidence document failed in ParseAndRouteWorkflow for inbox_id={}: {}", inboxId, e.getMessage());
            }
        }
    }
}
