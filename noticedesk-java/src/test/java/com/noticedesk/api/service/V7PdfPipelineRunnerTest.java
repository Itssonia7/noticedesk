package com.noticedesk.api.service;

import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.ocr.OcrFactory;
import com.noticedesk.api.service.ocr.OcrProvider;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in end-to-end run from PDF to v7 draft, under the live-run guard (commit check, run cap, per-notice cap, fail
 * fast). Skipped unless {@code ENABLE_E2E_TESTS=true} and {@code V7_PDF_NOTICES} are set.
 *
 * <p>{@code V7_PDF_NOTICES} = comma list of file names under {@code $PRIVATE_SEED_DIR/pdf/} (or absolute paths). For each
 * PDF, in order:
 * <ol>
 *   <li>OCR through the app's own {@code OcrFactory.getPrimaryProvider().process(bytes, filename, contentType)}, as the
 *       upload path does ({@code OCR_PROVIDER_PRIMARY=gemini}, {@code GEMINI_API_KEY}; model {@code OCR_GEMINI_MODEL},
 *       else {@code GEMINI_MODEL}); the text is saved to {@code testbench/results/<notice-id>_ocr.txt};</li>
 *   <li>the document_type label the app gives after OCR (DocumentParsingAgent, model {@code LLM_MODEL_PARSING});</li>
 *   <li>the v7 pipeline on the OCR text with that label, as {@link V7LivePipelineRunnerTest} (same report lines plus
 *       "OCR:" and "Triage:"); the draft is saved to {@code testbench/results/<notice-id>_v7_draft.txt}.</li>
 * </ol>
 * Notice id = file name without extension, lower-cased, non-alphanumerics to '-'. No answer key, no scoring. Whether
 * the OCR response names the project / tier is printed once ("tier: not reported by API" when it does not).
 */
public class V7PdfPipelineRunnerTest {

    @Test
    void runV7PdfPipeline() throws Exception {
        String enableE2e = env("ENABLE_E2E_TESTS");
        String pdfList = env("V7_PDF_NOTICES");
        Assumptions.assumeTrue("true".equalsIgnoreCase(enableE2e), "ENABLE_E2E_TESTS not set to true");
        Assumptions.assumeTrue(pdfList != null && !pdfList.isBlank(), "V7_PDF_NOTICES not set");

        String apiKey = env("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) fail("REFUSED: ANTHROPIC_API_KEY is not set (the v7 pipeline needs it)");

        // every PDF is resolved before any call
        List<File> pdfs;
        try {
            pdfs = PdfNoticeSteps.resolvePdfs(pdfList, env("PRIVATE_SEED_DIR"));
        } catch (IllegalArgumentException e) {
            fail("REFUSED: " + e.getMessage());
            return;
        }

        // OCR and triage models must be priced as well (a zero-priced model passes; a missing one refuses the run)
        AppProperties yml = TestAppProperties.fromApplicationYml();
        LiveRunGuard guard = LiveRunGuard.fromEnvironment();
        String ocrModel = yml.getOcr().getGemini().getModel() != null && !yml.getOcr().getGemini().getModel().isBlank()
                ? yml.getOcr().getGemini().getModel() : yml.getLlm().getGemini().getModel();
        String parsingModel = guard.model("LLM_MODEL_PARSING", yml.modelForAgent("parsing", "anthropic"));
        if (parsingModel == null) fail("REFUSED: set LLM_MODEL_PARSING (no model names are built into the runner)");

        V7LiveRig rig = V7LiveRig.create(apiKey, guard, List.of(ocrModel, parsingModel));
        rig.props.getLlm().setModelParsing(parsingModel);

        // the app's own OCR path
        OcrProvider ocrProvider = new OcrFactory(rig.props).getPrimaryProvider();
        if ("stub".equals(ocrProvider.getName())) {
            fail("REFUSED: the primary OCR provider is the stub; set OCR_PROVIDER_PRIMARY=gemini and GEMINI_API_KEY");
        }
        DocumentParsingAgent parsingAgent = new DocumentParsingAgent(rig.llmFactory, new IdentityService(null), rig.props, rig.om);
        ApiUsageLogService usage = rig.usageLogService;
        System.out.printf("OCR: provider=%s model=%s | triage model=%s%n", ocrProvider.getName(), ocrModel, parsingModel);

        if (guard.smoke()) {
            pdfs = pdfs.subList(0, 1);
            System.out.println("LIVE_SMOKE=true: only " + pdfs.get(0).getName() + " is run");
        }

        BigDecimal totalInr = BigDecimal.ZERO;
        Map<String, String> statusByNotice = new LinkedHashMap<>();
        String abortReason = null;
        boolean tierPrinted = false;

        System.out.println("=== V7 PDF PIPELINE EXECUTION REPORT ===");

        for (File pdf : pdfs) {
            String stop = guard.stopBeforeNotice(totalInr);
            if (stop != null) {
                System.out.println("RUN STOPPED: " + stop + " (run spend INR " + totalInr + "); remaining PDFs not run.");
                break;
            }
            String noticeId = PdfNoticeSteps.noticeIdFor(pdf.getName());
            UUID noticeUuid = V7LiveRig.noticeUuid(noticeId);
            byte[] bytes = Files.readAllBytes(pdf.toPath());
            System.out.printf("%n--- OCR and triage for %s ---%n", noticeId);

            // (1) OCR
            PdfNoticeSteps.OcrStep ocr;
            try {
                ocr = PdfNoticeSteps.ocr(ocrProvider, bytes, pdf.getName(), PdfNoticeSteps.contentType(pdf.getName()), usage,
                        V7LiveRig.TENANT, noticeUuid);
            } catch (LlmFatalException e) {
                statusByNotice.put(noticeId, "ABORTED");
                abortReason = e.getMessage();
                break;
            } catch (RuntimeException e) {
                statusByNotice.put(noticeId, "FAILED (OCR: " + e.getClass().getSimpleName() + ")");
                System.out.printf("Notice %s: OCR FAILED (%s)%n", noticeId, e.getClass().getSimpleName());
                if (guard.smoke()) break;
                continue;
            }
            String ocrLine = PdfNoticeSteps.ocrLine(ocr);
            System.out.println(ocrLine);
            if (!tierPrinted) {
                System.out.println(PdfNoticeSteps.tierLine(ocr.result()));
                tierPrinted = true;
            }
            File ocrFile = PdfNoticeSteps.saveOcrText(rig.resultsDir, noticeId, ocr.result().text());
            System.out.println("Saved OCR text to: " + ocrFile.getAbsolutePath());
            BigDecimal pre = ocr.costInr() != null ? ocr.costInr() : BigDecimal.ZERO;

            // (2) the document_type label the app gives after OCR
            PdfNoticeSteps.TriageStep triage;
            try {
                triage = PdfNoticeSteps.triage(parsingAgent, noticeId, pdf.getName(), ocr.result(), usage, V7LiveRig.TENANT, noticeUuid);
            } catch (LlmFatalException e) {
                totalInr = totalInr.add(pre);
                statusByNotice.put(noticeId, "ABORTED");
                abortReason = e.getMessage();
                break;
            } catch (RuntimeException e) {
                totalInr = totalInr.add(pre);
                statusByNotice.put(noticeId, "FAILED (triage: " + e.getClass().getSimpleName() + ")");
                System.out.printf("Notice %s: TRIAGE FAILED (%s)%n", noticeId, e.getClass().getSimpleName());
                if (guard.smoke()) break;
                continue;
            }
            String triageLine = PdfNoticeSteps.triageLine(triage);
            System.out.println(triageLine);
            if (triage.costInr() != null) pre = pre.add(triage.costInr());

            // (3) the v7 pipeline on the OCR text with the triage label
            V7LiveRig.NoticeOutcome outcome = rig.runNotice(noticeId, pdf.getName(), triage.documentType(), ocr.result().text(),
                    "none (PDF notice, not scored)", List.of(ocrLine, triageLine), pre);
            totalInr = totalInr.add(outcome.spentInr());
            statusByNotice.put(noticeId, outcome.status());
            if (outcome.abortReason() != null) {
                abortReason = outcome.abortReason();
                break;
            }
            if (outcome.stopRun()) break;
            if (outcome.result() == null && guard.smoke()) break;
        }

        rig.printSummary("PDF Run", statusByNotice, totalInr, abortReason);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : System.getProperty(name);
    }
}
