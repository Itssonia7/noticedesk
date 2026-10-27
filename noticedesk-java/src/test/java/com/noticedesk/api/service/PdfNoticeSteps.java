package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.ocr.LocalOcrProvider;
import com.noticedesk.api.service.ocr.OcrProvider;
import com.noticedesk.api.service.ocr.OcrResult;
import com.noticedesk.api.service.privacy.AnonymisationMap;
import com.noticedesk.api.service.privacy.NoticeAnonymiser;

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
import java.util.function.Function;

/**
 * The steps of {@link V7PdfPipelineRunnerTest} before the v7 pipeline: OCR through the configured OCR provider
 * ({@code OCR_PROVIDER}, default {@code local}: PDFBox text layer / tesseract, no network), anonymisation of the text
 * ({@code NOTICE_ANONYMISE}, default on), then the document parsing agent that labels {@code document_type}
 * (ParseAndRouteWorkflow) on the ANONYMISED text. Each billed call is logged to the usage service, so the run ledger,
 * the run cap and the fail-fast guard see it. No database or network access of its own.
 */
final class PdfNoticeSteps {

    private PdfNoticeSteps() {}

    static final String OCR_STEP = "ocr";
    static final String TRIAGE_STEP = "triage";

    /** Env: OCR provider of the runner (default local). */
    static final String OCR_PROVIDER_ENV = "OCR_PROVIDER";
    static final String DEFAULT_OCR_PROVIDER = LocalOcrProvider.NAME;
    /** Env: "off" switches the anonymiser off (and accepts a non-local OCR provider); anything else = on. */
    static final String ANONYMISE_ENV = "NOTICE_ANONYMISE";

    // ---------------------------------------------------------------- privacy settings

    /** OCR provider name for the runner: {@code OCR_PROVIDER}, else local. */
    static String ocrProviderName(String envValue) {
        return envValue == null || envValue.isBlank() ? DEFAULT_OCR_PROVIDER : envValue.trim().toLowerCase(Locale.ROOT);
    }

    /** True unless {@code NOTICE_ANONYMISE} is explicitly "off" / "false" / "0". */
    static boolean anonymise(String envValue) {
        if (envValue == null || envValue.isBlank()) return true;
        String v = envValue.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no"));
    }

    /**
     * Why the run must be refused, or null: a non-local OCR provider sends the raw document bytes (the client's name,
     * GSTIN, everything) to a model before the anonymiser can run, which the free-tier key makes unacceptable unless
     * the operator switched the anonymiser off explicitly ({@code NOTICE_ANONYMISE=off}).
     */
    static String privacyRefusal(String ocrProvider, boolean anonymise) {
        if (DEFAULT_OCR_PROVIDER.equals(ocrProvider) || !anonymise) return null;
        return "REFUSED: OCR_PROVIDER=" + ocrProvider + " sends the raw PDF bytes to a model before anonymisation (the "
                + "firm's gemini key is on the free tier, whose data may be used for training). Use OCR_PROVIDER=local "
                + "(PDFBox text layer, tesseract for scans) or set NOTICE_ANONYMISE=off to accept that explicitly.";
    }

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
            // the local provider (PDFBox / tesseract) has no price entry: it costs nothing
            if (LocalOcrProvider.NAME.equals(u.provider())) cost = BigDecimal.ZERO;
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

    // ---------------------------------------------------------------- anonymisation

    /**
     * The anonymised notice: the text every model call sees, the mapping (real values, kept only under
     * {@code $PRIVATE_SEED_DIR/results}) and the restorer applied to every output before it is saved.
     */
    record AnonymisedNotice(String text, AnonymisationMap map, boolean on) {
        /** Restores the real values in {@code s}; identity when the anonymiser is off. */
        String restore(String s) {
            return on ? NoticeAnonymiser.deAnonymise(s, map) : s;
        }

        Function<String, String> restorer() {
            return on ? this::restore : null;
        }

        String privacyLine() {
            return on ? "Privacy: anonymised " + map.summaryLine() : "Privacy: NOTICE_ANONYMISE=off, the real text was used";
        }
    }

    /** Anonymises the OCR text (identity with the same text and an empty map when {@code on} is false). */
    static AnonymisedNotice anonymise(String ocrText, boolean on) {
        if (!on) return new AnonymisedNotice(ocrText, new AnonymisationMap(), false);
        NoticeAnonymiser.Result r = NoticeAnonymiser.anonymise(ocrText);
        return new AnonymisedNotice(r.text(), r.map(), true);
    }

    /**
     * Saves the anonymised text as {@code <noticeId>_ocr_anon.txt} and the mapping as {@code <noticeId>_anon_map.json}
     * (the mapping holds the real values: {@code resultsDir} must be under {@code $PRIVATE_SEED_DIR}, never a folder
     * inside a git working tree). Returns the two files (the map file is null when the anonymiser is off).
     */
    static File[] saveAnonymised(File resultsDir, String noticeId, AnonymisedNotice a, ObjectMapper om) throws IOException {
        if (insideGitWorkTree(resultsDir)) {
            throw new IOException("REFUSED: " + resultsDir.getAbsolutePath() + " is inside a git working tree; the anonymisation "
                    + "map holds real client identifiers and may only be written under $PRIVATE_SEED_DIR/results");
        }
        File text = new File(resultsDir, noticeId + "_ocr_anon.txt");
        Files.writeString(text.toPath(), a.text() != null ? a.text() : "", StandardCharsets.UTF_8);
        File map = null;
        if (a.on()) {
            map = new File(resultsDir, noticeId + "_anon_map.json");
            a.map().writeTo(map, om);
        }
        return new File[]{text, map};
    }

    /** True when {@code dir} (or a parent) holds a {@code .git} entry. */
    static boolean insideGitWorkTree(File dir) {
        File d = dir.getAbsoluteFile();
        while (d != null) {
            if (new File(d, ".git").exists()) return true;
            d = d.getParentFile();
        }
        return false;
    }

    /**
     * The results folder of the PDF runner: {@code $PRIVATE_SEED_DIR/results} (created). Null when
     * {@code PRIVATE_SEED_DIR} is unset: the runner refuses to start, because the de-anonymised draft and the mapping
     * have no place outside the repository then.
     */
    static File privateResultsDir(String privateSeedDir) {
        if (privateSeedDir == null || privateSeedDir.isBlank()) return null;
        File dir = new File(privateSeedDir.trim(), "results");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    // ---------------------------------------------------------------- triage

    record TriageStep(String documentType, String provider, String model, BigDecimal costInr) {}

    /**
     * The document_type label the app gives after OCR (ParseAndRouteWorkflow -> DocumentParsingAgent); usage logged as
     * step "triage". "needs_review" when the agent could not place the document. {@code text} is the text the agent
     * sees: the ANONYMISED text in the PDF runner. The prompt also carries the file name, which on a laptop is often
     * "<client> SCN.pdf": with an anonymised text the agent is given a neutral name ({@link #neutralFilename}) instead.
     */
    static TriageStep triage(DocumentParsingAgent agent, String noticeId, String filename, OcrResult ocr, String text,
                             ApiUsageLogService usage, UUID tenantId, UUID noticeUuid) {
        boolean anonymised = text != null && !text.equals(ocr.text());
        DocumentParsingAgent.ParseInput in = new DocumentParsingAgent.ParseInput(noticeId, anonymised ? neutralFilename(filename) : filename,
                "web_upload", text != null ? text : ocr.text(), ocr.providerName(), ocr.pageCount());
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

    /** "notice.pdf" / "notice.png": the extension of {@code filename} on a name that says nothing about the client. */
    static String neutralFilename(String filename) {
        String n = filename != null ? filename : "";
        int dot = n.lastIndexOf('.');
        String ext = dot > 0 && dot < n.length() - 1 ? n.substring(dot).toLowerCase(Locale.ROOT) : ".pdf";
        return "notice" + ext;
    }

    /** As {@link #triage(DocumentParsingAgent, String, String, OcrResult, String, ApiUsageLogService, UUID, UUID)} on the OCR text itself. */
    static TriageStep triage(DocumentParsingAgent agent, String noticeId, String filename, OcrResult ocr,
                             ApiUsageLogService usage, UUID tenantId, UUID noticeUuid) {
        return triage(agent, noticeId, filename, ocr, ocr.text(), usage, tenantId, noticeUuid);
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
