package com.noticedesk.api.controller;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.exception.AppValidationException;
import com.noticedesk.api.exception.NotFoundException;
import com.noticedesk.api.service.ocr.OcrFactory;
import com.noticedesk.api.service.storage.StorageFactory;
import com.noticedesk.api.security.TenantContextHolder;
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

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties properties;
    private final StorageFactory storageFactory;
    private final OcrFactory ocrFactory;
    private final com.noticedesk.api.workflow.ParseAndRouteWorkflow parseAndRouteWorkflow;

    @PostMapping("/documents/upload")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file) throws Exception {
        String tenantId = TenantContextHolder.getTenantId();

        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", tenantId), String.class);

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

        UUID inboxId = jdbc.queryForObject(
                """
                INSERT INTO documents_inbox
                    (tenant_id, original_filename, s3_key, file_size_bytes, file_hash, mime_type, ingest_channel)
                VALUES (:tid::uuid, :filename, :key, :size, :hash, :mime, 'web_upload')
                RETURNING inbox_id
                """,
                Map.of(
                        "tid", tenantId,
                        "filename", filename,
                        "key", key,
                        "size", file.getSize(),
                        "hash", fileHash,
                        "mime", contentType),
                UUID.class);

        log.info("Document uploaded: inbox_id={} filename={} tenant={}", inboxId, filename, tenantId);

        // Trigger OCR inline
        try {
            var ocrResult = ocrFactory.getPrimaryProvider().process(bytes, filename, contentType);
            String ocrText = ocrResult.text();
            String ocrProvider = ocrResult.providerName();

            jdbc.update(
                    """
                    UPDATE documents_inbox
                    SET ocr_text = :text, ocr_provider_used = :provider, ocr_status = 'completed',
                        ocr_completed_at = NOW()
                    WHERE inbox_id = :id
                    """,
                    Map.of("text", ocrText, "provider", ocrProvider, "id", inboxId));

            // Trigger parsing and routing workflow
            try {
                parseAndRouteWorkflow.run(inboxId, UUID.fromString(tenantId));
            } catch (Exception e) {
                log.error("Failed to run parse and route for inbox_id={}: {}", inboxId, e.getMessage(), e);
            }

        } catch (Exception e) {
            log.warn("OCR failed for inbox_id={}: {}", inboxId, e.getMessage());
            jdbc.update(
                    "UPDATE documents_inbox SET ocr_status = 'failed' WHERE inbox_id = :id",
                    Map.of("id", inboxId));
        }

        return Map.of(
                "inbox_id", inboxId,
                "filename", filename,
                "status", "ocr_complete");
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
                SELECT inbox_id, original_filename, file_size_bytes, mime_type, ingest_channel,
                       ocr_status, ocr_provider_used, ocr_error, page_count,
                       parse_status, routing_status, routing_anomaly_details, parsed_to_notice_id,
                       uploaded_at
                FROM documents_inbox
                ORDER BY uploaded_at DESC
                LIMIT :limit OFFSET :offset
                """,
                Map.of("limit", page_size, "offset", offset));

        return Map.of(
                "items", items,
                "total", items.size(),
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
