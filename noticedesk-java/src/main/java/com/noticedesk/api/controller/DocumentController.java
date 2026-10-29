package com.noticedesk.api.controller;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.exception.AppValidationException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.security.AuthClaims;
import com.noticedesk.api.security.TenantContextHolder;
import com.noticedesk.api.service.ocr.OcrFactory;
import com.noticedesk.api.service.storage.StorageFactory;
import com.noticedesk.api.workflow.ParseAndRouteWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties properties;
    private final StorageFactory storageFactory;
    private final OcrFactory ocrFactory;
    private final ParseAndRouteWorkflow parseAndRouteWorkflow;

    @PostMapping("/documents/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "ingest_channel", defaultValue = "web_upload") String ingestChannel) throws Exception {

        String tenantId = TenantContextHolder.getTenantId();

        long maxBytes = properties.getStorage().getMaxUploadBytes();
        if (file.getSize() > maxBytes) {
            throw new AppValidationException(
                    String.format("File size %d exceeds maximum allowed %d bytes", file.getSize(), maxBytes));
        }

        String key = UUID.randomUUID().toString();
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        byte[] bytes = file.getBytes();
        String fileHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        storageFactory.getStorage().store(bytes, key, contentType);

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";

        UUID inboxId = insertInboxRecord(tenantId, filename, key, file.getSize(), fileHash, contentType, ingestChannel);

        log.info("Document uploaded: inbox_id={} filename={} tenant={}", inboxId, filename, tenantId);

        CompletableFuture.runAsync(() -> {
            try {
                TenantContextHolder.set(new AuthClaims(null, tenantId, null));
                processOcrAndWorkflow(inboxId, tenantId, bytes, filename, contentType);
            } catch (Exception e) {
                log.error("Background OCR/Workflow execution failed for inbox_id={}: {}", inboxId, e.getMessage(), e);
            } finally {
                TenantContextHolder.clear();
            }
        });

        return Map.of(
                "inbox_id", inboxId,
                "filename", filename,
                "ocr_status", "pending",
                "file_hash", fileHash,
                "file_size_bytes", file.getSize(),
                "status", "uploaded");
    }

    @Transactional
    public UUID insertInboxRecord(String tenantId, String filename, String key, long size, String hash, String contentType, String ingestChannel) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        return jdbc.queryForObject(
                """
                INSERT INTO documents_inbox
                    (tenant_id, original_filename, s3_key, file_size_bytes, file_hash, mime_type, ingest_channel, ocr_status, parse_status, routing_status)
                VALUES (:tid::uuid, :filename, :key, :size, :hash, :mime, :channel, 'pending', 'pending', 'pending')
                RETURNING inbox_id
                """,
                Map.of(
                        "tid", tenantId,
                        "filename", filename,
                        "key", key,
                        "size", size,
                        "hash", hash,
                        "mime", contentType,
                        "channel", ingestChannel != null ? ingestChannel : "web_upload"),
                UUID.class);
    }

    public void processOcrAndWorkflow(UUID inboxId, String tenantId, byte[] bytes, String filename, String contentType) {
        try {
            updateOcrStatus(tenantId, inboxId, "in_progress", null);

            var ocrResult = ocrFactory.getPrimaryProvider().process(bytes, filename, contentType);
            String ocrText = ocrResult.text();
            String ocrProvider = ocrResult.providerName();

            saveOcrCompleted(tenantId, inboxId, ocrText, ocrProvider);

            try {
                parseAndRouteWorkflow.run(inboxId, UUID.fromString(tenantId));
            } catch (Exception e) {
                log.error("Failed to run parse and route for inbox_id={}: {}", inboxId, e.getMessage(), e);
            }

        } catch (Exception e) {
            log.warn("OCR failed for inbox_id={}: {}", inboxId, e.getMessage());
            saveOcrFailed(tenantId, inboxId, e.getMessage());
        }
    }

    @Transactional
    public void updateOcrStatus(String tenantId, UUID inboxId, String status, String error) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);
        jdbc.update("UPDATE documents_inbox SET ocr_status = :status, ocr_error = :err WHERE inbox_id = :id",
                Map.of("status", status, "err", error != null ? error : "", "id", inboxId));
    }

    @Transactional
    public void saveOcrCompleted(String tenantId, UUID inboxId, String ocrText, String ocrProvider) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);
        jdbc.update(
                """
                UPDATE documents_inbox
                SET ocr_text = :text, ocr_provider_used = :provider, ocr_status = 'completed',
                    ocr_completed_at = NOW()
                WHERE inbox_id = :id
                """,
                Map.of("text", ocrText, "provider", ocrProvider, "id", inboxId));
    }

    @Transactional
    public void saveOcrFailed(String tenantId, UUID inboxId, String error) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);
        jdbc.update("UPDATE documents_inbox SET ocr_status = 'failed', ocr_error = :err WHERE inbox_id = :id",
                Map.of("err", error != null ? error : "OCR failed", "id", inboxId));
    }

    @GetMapping({"/inbox", "/documents/inbox"})
    @Transactional
    public Map<String, Object> listInbox(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int page_size) {

        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        int offset = (page - 1) * page_size;

        List<Map<String, Object>> items = jdbc.queryForList(
                """
                SELECT di.inbox_id, di.original_filename, di.file_size_bytes, di.page_count,
                       di.mime_type, di.ocr_status, di.ocr_provider_used, di.ocr_error,
                       di.ingest_channel, di.parse_status, di.routing_status,
                       di.routing_anomaly_details, di.parsed_to_notice_id,
                       c.legal_name AS matched_client_name,
                       CASE
                         WHEN r.registration_type = 'GST' THEN COALESCE(r.state_name, r.state_code) || ' GST'
                         WHEN r.registration_type = 'IT' THEN 'Income Tax'
                         ELSE NULL
                       END AS matched_registration_label,
                       n.document_type,
                       n.parse_confidence,
                       di.uploaded_at
                FROM documents_inbox di
                LEFT JOIN notices n ON n.notice_id = di.parsed_to_notice_id
                LEFT JOIN clients c ON c.client_id = n.client_id
                LEFT JOIN client_registrations r ON r.registration_id = n.registration_id
                ORDER BY di.uploaded_at DESC
                LIMIT :limit OFFSET :offset
                """,
                Map.of("limit", page_size, "offset", offset));

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM documents_inbox",
                Map.of(),
                Integer.class);

        return Map.of(
                "items", items,
                "total", total != null ? total : items.size(),
                "page", page,
                "page_size", page_size);
    }

    @GetMapping({"/inbox/{id}/ocr", "/documents/inbox/{id}/ocr"})
    @Transactional
    public Map<String, Object> getInboxOcr(@PathVariable UUID id) {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

        var rows = jdbc.queryForList(
                """
                SELECT inbox_id, ocr_status, ocr_text,
                       ocr_provider_used, page_count, ocr_error
                FROM documents_inbox WHERE inbox_id = :id
                """,
                Map.of("id", id));

        if (rows.isEmpty()) {
            throw new NotFoundException("Inbox item not found: " + id);
        }

        return new HashMap<>(rows.get(0));
    }
}
