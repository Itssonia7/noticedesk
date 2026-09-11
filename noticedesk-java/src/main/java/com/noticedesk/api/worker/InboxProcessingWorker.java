package com.noticedesk.api.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.agent.DocumentParsingAgent.ParseInput;
import com.noticedesk.api.agent.DocumentParsingAgent.ParsedDocument;
import com.noticedesk.api.agent.NoticeRoutingAgent;
import com.noticedesk.api.agent.NoticeRoutingAgent.RoutingInput;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.rag.RagStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxProcessingWorker {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final DocumentParsingAgent documentParsingAgent;
    private final NoticeRoutingAgent noticeRoutingAgent;
    private final RagStoreService ragStoreService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 15000)
    public void processPendingDocuments() {
        // Query all tenants (tenants table is not RLS restricted)
        List<UUID> tenants = jdbc.queryForList("SELECT tenant_id FROM tenants", Map.of(), UUID.class);

        for (UUID tenantId : tenants) {
            try {
                // Find pending documents for tenant
                List<Map<String, Object>> pendingDocs = transactionTemplate.execute(status -> {
                    jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", 
                            Map.of("tid", tenantId.toString()), String.class);
                    return jdbc.queryForList(
                            "SELECT inbox_id, tenant_id, ocr_status, ocr_text, ocr_provider_used, page_count, original_filename, ingest_channel " +
                            "FROM documents_inbox " +
                            "WHERE ocr_status = 'completed' " +
                            "  AND routing_status = 'pending' " +
                            "  AND parse_status = 'pending' " +
                            "LIMIT 5 FOR UPDATE SKIP LOCKED",
                            Map.of()
                    );
                });

                if (pendingDocs == null || pendingDocs.isEmpty()) {
                    continue;
                }

                for (Map<String, Object> doc : pendingDocs) {
                    UUID inboxId = (UUID) doc.get("inbox_id");

                    // Process each document in its own transaction
                    Boolean success = transactionTemplate.execute(status -> {
                        try {
                            jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", 
                                    Map.of("tid", tenantId.toString()), String.class);

                            // Claim the document for processing
                            int updated = jdbc.update(
                                    "UPDATE documents_inbox SET parse_status = 'in_progress' WHERE inbox_id = :id AND parse_status = 'pending'",
                                    Map.of("id", inboxId)
                            );
                            if (updated == 0) {
                                return true; // Someone else grabbed it
                            }

                            // Prepare input for parsing agent
                            ParseInput parseInput = new ParseInput(
                                    inboxId.toString(),
                                    (String) doc.get("original_filename"),
                                    (String) doc.get("ingest_channel"),
                                    (String) doc.get("ocr_text"),
                                    (String) doc.get("ocr_provider_used"),
                                    (Integer) doc.get("page_count")
                            );

                            log.info("Starting document parsing for inbox_id={}", inboxId);
                            ParsedDocument parsed = documentParsingAgent.parseDocument(parseInput);

                            // Update DB with parsed JSON and status
                            String parsedJsonStr = objectMapper.writeValueAsString(parsed.payload());
                            jdbc.update(
                                    "UPDATE documents_inbox " +
                                    "SET raw_parsed_json = CAST(:parsed AS JSONB), " +
                                    "    parse_status = 'completed' " +
                                    "WHERE inbox_id = :id",
                                    Map.of("id", inboxId, "parsed", parsedJsonStr)
                            );

                            // Emit parsing completed audit
                            Map<String, Object> auditState = new HashMap<>();
                            auditState.put("document_type", parsed.payload().get("document_type"));
                            auditState.put("law", parsed.payload().get("law"));
                            auditState.put("parse_confidence", parsed.payload().get("parse_confidence"));
                            auditState.put("model", parsed.model());
                            auditState.put("prompt_version", parsed.promptVersion());
                            auditState.put("provider", parsed.providerName());
                            auditState.put("input_tokens", parsed.inputTokens());
                            auditState.put("output_tokens", parsed.outputTokens());

                            auditService.emit(
                                    tenantId.toString(), null, "document.parsing.completed",
                                    "documents_inbox", inboxId.toString(),
                                    null, auditState, 1
                            );

                            // Proceed to routing
                            RoutingInput routingInput = new RoutingInput(tenantId, inboxId, parsed.payload());
                            log.info("Starting notice routing for inbox_id={}", inboxId);
                            NoticeRoutingAgent.RoutingResult routingResult = noticeRoutingAgent.route(routingInput, jdbc);

                            if (routingResult != null && routingResult.matterId() != null && "routed".equals(routingResult.status())) {
                                try {
                                    String ocrText = (String) doc.get("ocr_text");
                                    String filename = (String) doc.get("original_filename");
                                    String docType = parsed.payload() != null && parsed.payload().get("document_type") != null
                                            ? parsed.payload().get("document_type").toString()
                                            : "NOTICE";
                                    ragStoreService.indexEvidenceDocument(tenantId, routingResult.matterId(), inboxId, filename, ocrText, docType);
                                    log.info("Indexed evidence document for matter_id={} inbox_id={}", routingResult.matterId(), inboxId);
                                } catch (Exception e) {
                                    log.warn("Auto-indexing evidence document failed for inbox_id={}: {}", inboxId, e.getMessage());
                                }
                            }
                            return true;

                        } catch (Exception e) {
                            log.error("Failed to process inbox_id={} error={}", inboxId, e.getMessage(), e);
                            status.setRollbackOnly();
                            return false;
                        }
                    });

                    // If processing failed, record failure in a new clean transaction
                    if (Boolean.FALSE.equals(success)) {
                        transactionTemplate.execute(status -> {
                            jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)", 
                                    Map.of("tid", tenantId.toString()), String.class);
                            jdbc.update(
                                    "UPDATE documents_inbox SET parse_status = 'failed' WHERE inbox_id = :id",
                                    Map.of("id", inboxId)
                            );
                            
                            auditService.emit(
                                    tenantId.toString(), null, "document.parsing.failed",
                                    "documents_inbox", inboxId.toString(),
                                    null, Map.of("error", "Processing failed"), 2
                            );
                            return null;
                        });
                    }
                }
            } catch (Exception e) {
                log.error("Error processing tenant {}", tenantId, e);
            }
        }
    }
}
