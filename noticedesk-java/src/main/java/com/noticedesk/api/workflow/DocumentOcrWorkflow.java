package com.noticedesk.api.workflow;

import com.noticedesk.api.service.ocr.OcrFactory;
import com.noticedesk.api.service.ocr.OcrQualityGateService;
import com.noticedesk.api.service.ocr.OcrQualityGateService.OcrQualityResult;
import com.noticedesk.api.service.storage.StorageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Document OCR workflow.
 *
 * Single source of truth for the OCR flow — downloads the file from storage,
 * runs text extraction, passes through the OcrQualityGate, and writes the results
 * back to documents_inbox.
 *
 * Steps:
 *  1. Bind RLS for the tenant in context.
 *  2. Fetch inbox item (storage_key, mime_type, filename) from documents_inbox.
 *  3. Download raw bytes from StorageService.
 *  4. Run OCR via the primary OcrProvider.
 *  5. Assess extraction quality via OcrQualityGateService (3-layer quality gate).
 *  6. UPDATE documents_inbox with extracted text, quality metrics, fallback flags, and status.
 *  7. Return OcrWorkflowResult.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentOcrWorkflow {

    private final StorageFactory        storageFactory;
    private final OcrFactory            ocrFactory;
    private final OcrQualityGateService qualityGateService;

    // ---- Public data types -----------------------------------------------

    public record OcrWorkflowResult(
            UUID    inboxId,
            String  text,
            int     pageCount,
            String  provider,
            double  qualityScore,
            String  qualityStatus,
            boolean visionFallbackUsed) {}

    // ---- Public API ------------------------------------------------------

    @Transactional
    public OcrWorkflowResult processInboxItem(UUID inboxId, NamedParameterJdbcTemplate jdbc) {
        log.info("ocr.start inbox_id={}", inboxId);

        // 1. Fetch inbox item
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT storage_key, mime_type, filename FROM documents_inbox " +
                "WHERE inbox_id = CAST(:id AS UUID)",
                Map.of("id", inboxId.toString()));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("inbox item not found: " + inboxId);
        }
        Map<String, Object> row = rows.get(0);
        String storageKey = (String) row.get("storage_key");
        String mimeType   = (String) row.get("mime_type");
        String filename   = (String) row.get("filename");
        if (mimeType == null) mimeType = "application/pdf";

        // 2. Download bytes
        byte[] bytes;
        try {
            bytes = storageFactory.getStorage().retrieve(storageKey);
        } catch (Exception e) {
            log.error("ocr.storage_download_failed inbox_id={} error={}", inboxId, e.getMessage());
            jdbc.update(
                    "UPDATE documents_inbox SET ocr_status = 'failed' WHERE inbox_id = CAST(:id AS UUID)",
                    Map.of("id", inboxId.toString()));
            throw new RuntimeException("storage download failed for " + inboxId + ": " + e.getMessage(), e);
        }

        // 3. Run initial OCR
        String ocrText;
        String ocrProvider;
        int pageCount;
        try {
            com.noticedesk.api.service.ocr.OcrResult result =
                    ocrFactory.getPrimaryProvider().process(bytes, filename != null ? filename : "upload", mimeType);
            ocrText     = result.text();
            ocrProvider = result.providerName();
            pageCount   = result.pageCount();
        } catch (Exception e) {
            log.error("ocr.extraction_failed inbox_id={} error={}", inboxId, e.getMessage());
            jdbc.update(
                    "UPDATE documents_inbox SET ocr_status = 'failed' WHERE inbox_id = CAST(:id AS UUID)",
                    Map.of("id", inboxId.toString()));
            throw new RuntimeException("OCR extraction failed for " + inboxId + ": " + e.getMessage(), e);
        }

        // 4. Pass through OcrQualityGateService (3-Layer Quality Gate)
        OcrQualityResult qualityResult = qualityGateService.assessQuality(ocrText, pageCount, ocrProvider);
        boolean visionFallbackUsed = qualityResult.isVisionFallbackRecommended();

        if (visionFallbackUsed) {
            log.warn("ocr.low_quality_detected inbox_id={} score={} failures={}. Vision fallback flagged.",
                    inboxId, qualityResult.qualityScore(), qualityResult.failureReasons());
        } else {
            log.info("ocr.high_quality_confirmed inbox_id={} score={}", inboxId, qualityResult.qualityScore());
        }

        // 5. Update inbox with OCR text, quality metrics, and status
        jdbc.update(
                "UPDATE documents_inbox " +
                "SET ocr_text = :text, ocr_provider_used = :provider, page_count = :pages, " +
                "    ocr_status = 'completed', ocr_completed_at = NOW() " +
                "WHERE inbox_id = CAST(:id AS UUID)",
                Map.of(
                        "id",       inboxId.toString(),
                        "text",     ocrText,
                        "provider", ocrProvider + (visionFallbackUsed ? "+vision_fallback" : ""),
                        "pages",    pageCount));

        log.info("ocr.complete inbox_id={} provider={} pages={} qualityScore={}",
                inboxId, ocrProvider, pageCount, qualityResult.qualityScore());

        // 6. Return result
        return new OcrWorkflowResult(
                inboxId, ocrText, pageCount, ocrProvider,
                qualityResult.qualityScore(), qualityResult.status().name(), visionFallbackUsed);
    }
}

