package com.noticedesk.api.service;

import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.ocr.LocalOcrProvider;
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
 * <p>Privacy-first order (the firm's gemini key is on the free tier, so no client-identifying text may reach a model):
 * <ol>
 *   <li>local text: {@code OCR_PROVIDER} (default {@code local}: the PDF text layer through PDFBox, a scanned PDF
 *       through the system {@code tesseract}; nothing leaves the machine). A non-local provider is refused unless
 *       {@code NOTICE_ANONYMISE=off} is set explicitly;</li>
 *   <li>anonymise ({@code NoticeAnonymiser}, default on): GSTINs / PANs / names / addresses / reference numbers become
 *       same-shape placeholders; the anonymised text goes to {@code $PRIVATE_SEED_DIR/results/<notice-id>_ocr_anon.txt}
 *       and the mapping to {@code <notice-id>_anon_map.json} (never inside the repository);</li>
 *   <li>triage on the anonymised text ({@code DocumentParsingAgent}; provider {@code LLM_PROVIDER_TRIAGE}, default the
 *       primary Anthropic provider, model {@code LLM_MODEL_TRIAGE}, else {@code LLM_MODEL_PARSING}, else application.yml);</li>
 *   <li>the v7 pipeline on the anonymised text (shortlist, matching, writers, citation check; the learned library stores
 *       the anonymised text only);</li>
 *   <li>outputs de-anonymised before saving: {@code <notice-id>_draft_anon.txt} (as the models saw it),
 *       {@code <notice-id>_draft.txt}, {@code <notice-id>_draft.docx}, {@code <notice-id>_summary.txt} (restored).</li>
 * </ol>
 * {@code V7_PDF_NOTICES} = comma list of file names under {@code $PRIVATE_SEED_DIR/pdf/} (or absolute paths), all
 * resolved before any call. Notice id = file name without extension, lower-cased, non-alphanumerics to '-'. No answer
 * key, no scoring. One "Privacy:" line per notice gives the counts of replaced values (never a value).
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

        // privacy settings first: no file is read and no call is made when they refuse the run
        String ocrProviderName = PdfNoticeSteps.ocrProviderName(env(PdfNoticeSteps.OCR_PROVIDER_ENV));
        boolean anonymise = PdfNoticeSteps.anonymise(env(PdfNoticeSteps.ANONYMISE_ENV));
        String refusal = PdfNoticeSteps.privacyRefusal(ocrProviderName, anonymise);
        if (refusal != null) fail(refusal);
        File resultsDir = PdfNoticeSteps.privateResultsDir(env("PRIVATE_SEED_DIR"));
        if (resultsDir == null) {
            fail("REFUSED: PRIVATE_SEED_DIR is not set (or its results folder cannot be created); the anonymisation map and "
                    + "the restored draft are written only under $PRIVATE_SEED_DIR/results");
            return;
        }
        if (PdfNoticeSteps.insideGitWorkTree(resultsDir)) {
            fail("REFUSED: " + resultsDir.getAbsolutePath() + " is inside a git working tree; PRIVATE_SEED_DIR must be outside the repository");
        }

        // every PDF is resolved before any call
        List<File> pdfs;
        try {
            pdfs = PdfNoticeSteps.resolvePdfs(pdfList, env("PRIVATE_SEED_DIR"));
        } catch (IllegalArgumentException e) {
            fail("REFUSED: " + e.getMessage());
            return;
        }

        // triage provider / model (LLM_PROVIDER_TRIAGE, LLM_MODEL_TRIAGE; else LLM_MODEL_PARSING, else application.yml:
        // the primary Anthropic provider with model-parsing). The model must be priced (a zero-priced model passes).
        AppProperties yml = TestAppProperties.fromApplicationYml();
        LiveRunGuard guard = LiveRunGuard.fromEnvironment();
        String triageProvider = Optional.ofNullable(env("LLM_PROVIDER_TRIAGE")).map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isBlank()).orElse("anthropic");
        String parsingModel = guard.model("LLM_MODEL_TRIAGE", guard.model("LLM_MODEL_PARSING", yml.modelForAgent("parsing", triageProvider)));
        if (parsingModel == null) fail("REFUSED: set LLM_MODEL_TRIAGE (no model names are built into the runner)");
        if ("gemini".equals(triageProvider) && (yml.getLlm().getGemini().getApiKey() == null || yml.getLlm().getGemini().getApiKey().isBlank())) {
            fail("REFUSED: LLM_PROVIDER_TRIAGE=gemini but GEMINI_API_KEY is not set");
        }
        List<String> extraModels = new ArrayList<>(List.of(parsingModel));
        String ocrModel = null;
        if ("gemini".equals(ocrProviderName)) {
            ocrModel = yml.getOcr().getGemini().getModel() != null && !yml.getOcr().getGemini().getModel().isBlank()
                    ? yml.getOcr().getGemini().getModel() : yml.getLlm().getGemini().getModel();
            extraModels.add(ocrModel);
        }

        V7LiveRig rig = V7LiveRig.create(apiKey, guard, extraModels);
        rig.useResultsDir(resultsDir);
        rig.props.getLlm().setModelParsing(parsingModel);
        rig.props.getLlm().setProviderParsing(triageProvider);
        rig.props.getOcr().setProviderPrimary(ocrProviderName);

        // the OCR provider: local (PDFBox / tesseract) unless the operator chose otherwise and switched the anonymiser off
        OcrProvider ocrProvider = new OcrFactory(rig.props).getPrimaryProvider();
        if ("stub".equals(ocrProvider.getName())) {
            fail("REFUSED: OCR_PROVIDER=" + ocrProviderName + " resolves to the stub OCR provider; use local (default) or gemini");
        }
        if (ocrProvider instanceof LocalOcrProvider local) {
            System.out.println("OCR: local (PDFBox text layer; tesseract " + (local.tesseractAvailable() ? "found on PATH for scanned pages"
                    : "NOT on PATH: a scanned PDF will be refused, never sent to a model") + ")");
        }
        DocumentParsingAgent parsingAgent = new DocumentParsingAgent(rig.llmFactory, new IdentityService(null), rig.props, rig.om);
        ApiUsageLogService usage = rig.usageLogService;
        System.out.printf("OCR: provider=%s%s | anonymise=%s | triage=%s:%s | results=%s%n", ocrProvider.getName(),
                ocrModel != null ? " model=" + ocrModel : "", anonymise ? "on" : "OFF (NOTICE_ANONYMISE=off)", triageProvider, parsingModel,
                resultsDir.getAbsolutePath());

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
            System.out.printf("%n--- Local text, anonymisation and triage for %s ---%n", noticeId);

            // (1) local text (or the explicitly accepted non-local provider)
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
                System.out.printf("Notice %s: OCR FAILED (%s: %s)%n", noticeId, e.getClass().getSimpleName(), e.getMessage());
                if (guard.smoke()) break;
                continue;
            }
            String ocrLine = PdfNoticeSteps.ocrLine(ocr);
            System.out.println(ocrLine);
            if (!tierPrinted && !"local".equals(ocrProvider.getName())) {
                System.out.println(PdfNoticeSteps.tierLine(ocr.result()));
                tierPrinted = true;
            }
            BigDecimal pre = ocr.costInr() != null ? ocr.costInr() : BigDecimal.ZERO;

            // (2) anonymise; the anonymised text and the mapping go under $PRIVATE_SEED_DIR/results only
            PdfNoticeSteps.AnonymisedNotice anon = PdfNoticeSteps.anonymise(ocr.result().text(), anonymise);
            File[] saved = PdfNoticeSteps.saveAnonymised(resultsDir, noticeId, anon, rig.om);
            String privacyLine = anon.privacyLine();
            System.out.println(privacyLine);
            System.out.println("Saved anonymised text to: " + saved[0].getAbsolutePath()
                    + (saved[1] != null ? " (mapping: " + saved[1].getAbsolutePath() + ")" : ""));

            // (3) the document_type label, from the anonymised text
            PdfNoticeSteps.TriageStep triage;
            try {
                triage = PdfNoticeSteps.triage(parsingAgent, noticeId, pdf.getName(), ocr.result(), anon.text(), usage, V7LiveRig.TENANT, noticeUuid);
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

            // (4) the v7 pipeline on the anonymised text; (5) outputs restored before saving
            V7LiveRig.NoticeOutcome outcome = rig.runNotice(noticeId, pdf.getName(), triage.documentType(), anon.text(),
                    "none (PDF notice, not scored)", List.of(ocrLine, privacyLine, triageLine), pre, anon.restorer());
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
