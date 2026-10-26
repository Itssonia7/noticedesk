package com.noticedesk.api.service;

import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.ocr.OcrProvider;
import com.noticedesk.api.service.ocr.OcrResult;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The steps of {@link V7PdfPipelineRunnerTest} before the v7 pipeline, as the upload path runs them
 * ({@code DocumentController.processOcrAndWorkflow}): OCR through the primary OCR provider, then the document parsing
 * agent that labels {@code document_type} (ParseAndRouteWorkflow). Each billed call is logged to the usage service, so
 * the run ledger, the run cap and the fail-fast guard see it. No database or network access of its own.
 */
final class PdfNoticeSteps {

    private PdfNoticeSteps() {}

    static final String OCR_STEP = "ocr";
    static final String TRIAGE_STEP = "triage";

    /** Notice id of a PDF: the file name without extension, lower-cased, every run of non-alphanumerics replaced by '-'. */
    static String noticeIdFor(String fileName) {
        String name = new File(fileName).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return id.isEmpty() ? "notice" : id;
    }

    /**
     * Resolves {@code V7_PDF_NOTICES} (comma list): an absolute path as given, else {@code $PRIVATE_SEED_DIR/pdf/<name>}.
     *
     * @throws IllegalArgumentException naming the first entry found nowhere (the run is refused before any call)
     */
    static List<File> resolvePdfs(String csv, String privateSeedDir) {
        List<File> out = new ArrayList<>();
        if (csv == null) return out;
        for (String raw : csv.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            File f = new File(name);
            if (!f.isAbsolute()) {
                f = privateSeedDir != null && !privateSeedDir.isBlank() ? new File(new File(privateSeedDir.trim(), "pdf"), name) : null;
            }
            if (f == null || !f.isFile()) {
                throw new IllegalArgumentException("PDF not found: " + name
                        + (f == null ? " (PRIVATE_SEED_DIR is not set; give an absolute path)" : ""));
            }
            out.add(f);
        }
        return out;
    }

    /** Content type the upload path would record for the file. */
    static String contentType(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        return "application/pdf";
    }

    // ---------------------------------------------------------------- OCR

    /** One OCR run: the result, wall time and cost (null when the provider reports no usage or the model is unpriced). */
    record OcrStep(OcrResult result, long elapsedMs, BigDecimal costInr) {
        int chars() {
            return result.text() != null ? result.text().length() : 0;
        }
    }

    /** OCR exactly as the upload path: {@code provider.process(bytes, filename, contentType)}; usage logged as step "ocr". */
    static OcrStep ocr(OcrProvider provider, byte[] bytes, String filename, String contentType, ApiUsageLogService usage,
                       UUID tenantId, UUID noticeId) {
        long t0 = System.nanoTime();
        OcrResult r;
        try {
            r = provider.process(bytes, filename, contentType);
        } catch (RuntimeException e) {
            if (usage != null) usage.reportOutcome(OCR_STEP, 1, false, e.getClass().getSimpleName());
            throw e;
        }
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        BigDecimal cost = null;
        OcrResult.OcrUsage u = r.usage();
        if (usage != null && u != null) {
            cost = logAndPrice(usage, u.provider(), u.model(), u.inputTokens(), u.outputTokens(), OCR_STEP, tenantId, noticeId);
        }
        boolean usable = r.text() != null && !r.text().isBlank();
        if (usage != null) usage.reportOutcome(OCR_STEP, 1, usable, usable ? null : "no_text");
        return new OcrStep(r, elapsed, cost);
    }

    /** "OCR: provider=gemini model=... pages=3 chars=12345 elapsed=8.2s tokens in=.. out=.. cost INR 0.0000". */
    static String ocrLine(OcrStep s) {
        OcrResult r = s.result();
        OcrResult.OcrUsage u = r.usage();
        StringBuilder sb = new StringBuilder("OCR: provider=").append(r.providerName());
        if (u != null && u.model() != null) sb.append(" model=").append(u.model());
        sb.append(" pages=").append(r.pageCount()).append(" chars=").append(s.chars())
          .append(String.format(Locale.ROOT, " elapsed=%.1fs", s.elapsedMs() / 1000.0));
        if (u == null) {
            sb.append(" tokens not reported, cost not reported");
        } else {
            sb.append(" tokens in=").append(u.inputTokens() != null ? u.inputTokens() : "n/a")
              .append(" out=").append(u.outputTokens() != null ? u.outputTokens() : "n/a")
              .append(s.costInr() != null ? String.format(Locale.ROOT, " cost INR %.4f", s.costInr().doubleValue()) : " cost: model unpriced");
        }
        return sb.toString();
    }

    /** "tier: <fields>" when the OCR response named a project / tier field, else "tier: not reported by API". */
    static String tierLine(OcrResult r) {
        String info = r != null && r.usage() != null ? r.usage().tierInfo() : null;
        return "tier: " + (info != null && !info.isBlank() ? info : "not reported by API");
    }

    /** Saves the OCR text to {@code <resultsDir>/<noticeId>_ocr.txt}. */
    static File saveOcrText(File resultsDir, String noticeId, String text) throws IOException {
        File f = new File(resultsDir, noticeId + "_ocr.txt");
        Files.writeString(f.toPath(), text != null ? text : "", StandardCharsets.UTF_8);
        return f;
    }

    // ---------------------------------------------------------------- triage

    record TriageStep(String documentType, String provider, String model, BigDecimal costInr) {}

    /**
     * The document_type label the app gives after OCR (ParseAndRouteWorkflow -> DocumentParsingAgent); usage logged as
     * step "triage". "needs_review" when the agent could not place the document.
     */
    static TriageStep triage(DocumentParsingAgent agent, String noticeId, String filename, OcrResult ocr,
                             ApiUsageLogService usage, UUID tenantId, UUID noticeUuid) {
        DocumentParsingAgent.ParseInput in = new DocumentParsingAgent.ParseInput(noticeId, filename, "web_upload",
                ocr.text(), ocr.providerName(), ocr.pageCount());
        DocumentParsingAgent.ParsedDocument parsed;
        try {
            parsed = agent.parseDocument(in);
        } catch (RuntimeException e) {
            if (usage != null) usage.reportOutcome(TRIAGE_STEP, 1, false, e.getClass().getSimpleName());
            throw e;
        }
        BigDecimal cost = null;
        if (usage != null) {
            cost = logAndPrice(usage, parsed.providerName(), parsed.model(), parsed.inputTokens(), parsed.outputTokens(),
                    TRIAGE_STEP, tenantId, noticeUuid);
            usage.reportOutcome(TRIAGE_STEP, 1, true, null);
        }
        Object type = parsed.payload() != null ? parsed.payload().get("document_type") : null;
        String docType = type != null && !type.toString().isBlank() ? type.toString() : "needs_review";
        return new TriageStep(docType, parsed.providerName(), parsed.model(), cost);
    }

    /** "Triage: document_type=DRC-01 (anthropic:model, cost INR 0.0123)". */
    static String triageLine(TriageStep t) {
        return "Triage: document_type=" + t.documentType() + " (" + Objects.toString(t.provider(), "unknown") + ":"
                + Objects.toString(t.model(), "unknown")
                + (t.costInr() != null ? String.format(Locale.ROOT, ", cost INR %.4f", t.costInr().doubleValue()) : ", cost not reported") + ")";
    }

    // ---------------------------------------------------------------- helpers

    /** Logs one call (ledger + api_usage_logs) and returns its INR cost; null when the model has no price. */
    private static BigDecimal logAndPrice(ApiUsageLogService usage, String provider, String model, Integer in, Integer out,
                                          String step, UUID tenantId, UUID noticeId) {
        LlmResponse resp = new LlmResponse(null, model, provider, in, out, "end_turn", 0, 0, null);
        usage.logCall(resp, model, step, 1, tenantId, noticeId);
        if (model == null || !usage.hasPrice(model)) return null;
        return usage.calculateCost(model, in != null ? in : 0, out != null ? out : 0, 0, 0)[1];
    }
}
