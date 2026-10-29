package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.learned.LearnedLibrary;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.ocr.OcrException;
import com.noticedesk.api.service.ocr.OcrFactory;
import com.noticedesk.api.service.ocr.OcrProvider;
import com.noticedesk.api.service.ocr.OcrResult;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The PDF runner's steps offline: a stub OCR provider (with token usage), the document parsing agent on the stub LLM,
 * and the v7 pipeline with mocked agents. No network, no database, no paid call.
 */
class V7PdfPipelineRunnerOfflineTest {

    @TempDir
    Path tmp;

    private final ObjectMapper om = new ObjectMapper();

    static final String OCR_TEXT = "=== PAGE 1 ===\nFORM GST DRC-01\nSummary of Show Cause Notice\n"
            + "ITC of Rs 10,000 on hire charges is proposed to be denied under Section 17(5).\n=== PAGE 2 ===\nPara 4.";

    /** Stub OCR provider reporting usage as the gemini provider does. */
    static OcrProvider stubOcr(String model) {
        return new OcrProvider() {
            @Override
            public String getName() {
                return "gemini";
            }

            @Override
            public OcrResult process(byte[] fileBytes, String filename, String mimeType) {
                if (fileBytes == null || fileBytes.length == 0) throw new OcrException("empty");
                return new OcrResult(OCR_TEXT, 2, "gemini", new OcrResult.OcrUsage("gemini", model, 1800, 120, null));
            }
        };
    }

    @Test
    void noticeIdAndPdfResolution() throws Exception {
        assertEquals("rfd-08-show-cause-final", PdfNoticeSteps.noticeIdFor("RFD-08 Show Cause (Final).PDF"));
        assertEquals("drc01-a-2024", PdfNoticeSteps.noticeIdFor("/x/y/DRC01_A__2024.pdf"));

        Path pdfDir = Files.createDirectories(tmp.resolve("pdf"));
        Files.write(pdfDir.resolve("a.pdf"), new byte[]{1});
        Path abs = Files.write(tmp.resolve("b.pdf"), new byte[]{2});
        List<File> files = PdfNoticeSteps.resolvePdfs(" a.pdf , " + abs, tmp.toString());
        assertEquals(List.of(pdfDir.resolve("a.pdf").toFile(), abs.toFile()), files);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PdfNoticeSteps.resolvePdfs("a.pdf,zzz.pdf", tmp.toString()));
        assertTrue(missing.getMessage().contains("zzz.pdf"));
        assertThrows(IllegalArgumentException.class, () -> PdfNoticeSteps.resolvePdfs("a.pdf", null));
        assertEquals("application/pdf", PdfNoticeSteps.contentType("x.PDF"));
        assertEquals("image/png", PdfNoticeSteps.contentType("x.png"));
    }

    @Test
    void ocrModelIsPricedAndTheGeminiProviderIsWiredInTheFactory() {
        AppProperties yml = TestAppProperties.fromApplicationYml();
        String ocrModel = yml.getOcr().getGemini().getModel();
        assertFalse(ocrModel == null || ocrModel.isBlank(), "the OCR model has a default");
        assertTrue(new ApiUsageLogService(null, yml).hasPrice(ocrModel), "the configured OCR model has a price entry");

        AppProperties p = new AppProperties();
        p.getOcr().setProviderPrimary("gemini");
        p.getOcr().getGemini().setModel("test-ocr-model");
        p.getLlm().getGemini().setApiKey("test-key-not-real");
        OcrProvider provider = new OcrFactory(p).getPrimaryProvider();
        assertEquals("gemini", provider.getName());
        assertEquals("stub", new OcrFactory(new AppProperties()).getPrimaryProvider().getName());
    }

    @Test
    void ocrTriageAndPipelineStepsOnStubs() throws Exception {
        AppProperties props = TestAppProperties.fromApplicationYml();
        props.setPrivateSeedDir(tmp.resolve("private").toString());
        String ocrModel = props.getOcr().getGemini().getModel();
        ApiUsageLogService usage = new ApiUsageLogService(null, props);
        RunCostLedger ledger = RunCostLedger.attach(usage, System.out);
        UUID tenant = V7LiveRig.TENANT;
        String noticeId = PdfNoticeSteps.noticeIdFor("DRC-01 Hire Charges.pdf");
        UUID noticeUuid = V7LiveRig.noticeUuid(noticeId);

        // (1) OCR on the stub provider, logged to the ledger
        PdfNoticeSteps.OcrStep ocr = PdfNoticeSteps.ocr(stubOcr(ocrModel), new byte[]{1, 2, 3}, "DRC-01 Hire Charges.pdf",
                "application/pdf", usage, tenant, noticeUuid);
        assertEquals(OCR_TEXT.length(), ocr.chars());
        assertEquals(1, ledger.size(), "the OCR call is billed through the usage log");
        assertEquals("ocr", ledger.calls().get(0).step());
        String ocrLine = PdfNoticeSteps.ocrLine(ocr);
        assertTrue(ocrLine.startsWith("OCR: provider=gemini model=" + ocrModel + " pages=2 chars=" + OCR_TEXT.length()), ocrLine);
        assertTrue(ocrLine.contains("tokens in=1800 out=120 cost INR 0.0000"), ocrLine);
        assertEquals("tier: not reported by API", PdfNoticeSteps.tierLine(ocr.result()));
        assertEquals("tier: project=p1", PdfNoticeSteps.tierLine(new OcrResult("t", 1, "gemini",
                new OcrResult.OcrUsage("gemini", "m", 1, 1, "project=p1"))));

        File resultsDir = Files.createDirectories(tmp.resolve("results")).toFile();
        File ocrFile = PdfNoticeSteps.saveOcrText(resultsDir, noticeId, ocr.result().text());
        assertEquals("drc-01-hire-charges_ocr.txt", ocrFile.getName());
        assertEquals(OCR_TEXT, Files.readString(ocrFile.toPath(), StandardCharsets.UTF_8));

        // (2) triage on the stub LLM
        AppProperties stubProps = new AppProperties();
        stubProps.getLlm().setProviderPrimary("stub");
        DocumentParsingAgent parsing = new DocumentParsingAgent(new LlmFactory(stubProps), new IdentityService(null), stubProps, om);
        PdfNoticeSteps.TriageStep triage = PdfNoticeSteps.triage(parsing, noticeId, "DRC-01 Hire Charges.pdf", ocr.result(),
                usage, tenant, noticeUuid);
        assertEquals("needs_review", triage.documentType());
        assertTrue(PdfNoticeSteps.triageLine(triage).startsWith("Triage: document_type=needs_review (stub:stub-model"),
                PdfNoticeSteps.triageLine(triage));

        // (3) the v7 pipeline on the OCR text with the triage label; the unmatched issue goes to the learned library
        LearnedLibrary library = new LearnedLibrary(props, om);
        library.setSourceRun("0123456789abcdef");
        V7DraftingPipelineService pipeline = mockedPipeline(props);
        pipeline.setLearnedLibrary(library);
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("notice_id", noticeId);
        notice.put("document_type", triage.documentType());
        DraftingInput input = new DraftingInput(noticeUuid, "formal", null, null, null, null, "Regular", null, null,
                "CGST Act", null, null, notice, Map.of(), ocr.result().text(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null);
        DraftingPipelineResult r = pipeline.runPipeline(input, tenant, noticeUuid);

        List<String> learned = V7LiveRig.learnedLines(r);
        assertEquals(1, learned.size());
        assertTrue(learned.get(0).matches("Learned: saved LRN-[0-9a-f]{8} for issue #1"), learned.get(0));
        assertEquals("needs_review", library.entries().get(0).documentType());
        assertTrue(Files.list(tmp.resolve("private/learned")).anyMatch(f -> f.getFileName().toString().endsWith("_drc-01-hire-charges_issue1.json")));
        assertFalse(V7LivePipelineRunnerTest.renderDraft(noticeId, r).isBlank());
    }

    @Test
    void ocrFailureIsReportedToTheFailFastGuard() {
        ApiUsageLogService usage = new ApiUsageLogService(null, new AppProperties());
        List<ApiUsageLogService.CallOutcome> outcomes = new ArrayList<>();
        usage.addOutcomeListener(outcomes::add);
        assertThrows(OcrException.class, () -> PdfNoticeSteps.ocr(stubOcr("m"), new byte[0], "x.pdf", "application/pdf",
                usage, V7LiveRig.TENANT, UUID.randomUUID()));
        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0).usable());
        assertEquals("ocr", outcomes.get(0).step());
    }

    private V7DraftingPipelineService mockedPipeline(AppProperties props) {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer.</p>", "15", "<p>Checklist.</p>"), null, null, null));
        IssueMatchingAgent matching = mock(IssueMatchingAgent.class);
        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/9", null, "01-04-2024", null, "2022-23", "2022-23", 10000.0, "Officer");
        when(matching.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(new MatchingResult(info,
                List.of(new MatchedIssue(1, "none", List.of(), "ITC on hire charges denied", List.of(),
                        Map.of("period", "2022-23", "amount", 10000.0, "legal_section", "Section 17(5)"), List.of("4"), List.of())),
                Map.of("issue", List.of(1)), List.of(), List.of(), "h", 1, 1, 0, 0));
        when(newIssue.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(1, "<p>Hire charges are not a blocked credit.</p>", null,
                        "<p>Section 16(2) conditions are met.</p>", List.of(), null, List.of("Hire agreements"), "Summary.",
                        "test-model-new", "new_issue_v2"));
        return new V7DraftingPipelineService(matching, null, om, fill, assembly, new DraftCheckService(null),
                null, newIssue, mock(HighStakesDraftingAgent.class), null, null, props, null);
    }
}
