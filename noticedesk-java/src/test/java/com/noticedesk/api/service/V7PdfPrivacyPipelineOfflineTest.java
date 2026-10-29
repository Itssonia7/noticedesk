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
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.learned.LearnedLibrary;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.ocr.LocalOcrProvider;
import com.noticedesk.api.service.privacy.AnonymisationMap;
import com.noticedesk.api.service.privacy.NoticeAnonymiser;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The privacy-first PDF path offline, on a PDF built in the test (fictional party "ZETA ALLOYS", allow-listed
 * placeholder GSTINs): local text through PDFBox at cost 0, anonymisation, the files under a private results folder,
 * triage that sees only the anonymised text, the v7 pipeline (mocked agents) and the learned library on the anonymised
 * text, and the restored outputs. No network, no database, no paid call.
 */
class V7PdfPrivacyPipelineOfflineTest {

    @TempDir
    Path tmp;

    private final ObjectMapper om = new ObjectMapper();

    static final String TAXPAYER_GSTIN = "27ZZZZZ9999Z1Z9";
    static final String SUPPLIER_GSTIN = "24YYYYY8888Y1Z8";

    static final List<String> PAGE_1 = List.of(
            "FORM GST DRC-01",
            "Reference No.: ZD270326001234X   Dated: 24.05.2024",
            "To,",
            "M/s ZETA ALLOYS PRIVATE LIMITED",
            "Plot No. 12, MIDC Industrial Area, Andheri (East),",
            "Mumbai, Maharashtra - 400093",
            "GSTIN: 27ZZZZZ9999Z1Z9",
            "Sub: Show cause notice under Section 73 of the CGST Act, 2017 for FY 2019-20");
    static final List<String> PAGE_2 = List.of(
            "1. M/s Zeta Alloys Private Limited has availed ITC of Rs. 10,000 on hire charges from",
            "M/s Alpha Traders, GSTIN 24YYYYY8888Y1Z8, which is proposed to be denied under Section 17(5).",
            "Shri Rakesh Kumar Sharma, Director, appeared on 10.06.2024.");

    static byte[] pdf(List<List<String>> pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(font, 11);
                    float y = 800;
                    for (String line : lines) {
                        cs.beginText();
                        cs.newLineAtOffset(50, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 16;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** An LLM factory whose "parsing" provider records the prompt it gets and answers a fixed document type. */
    static final class CapturingFactory extends LlmFactory {
        final List<String> systemPrompts = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();

        CapturingFactory(AppProperties props) {
            super(props);
        }

        @Override
        public LlmProvider getLlmForAgent(String agentName) {
            return new LlmProvider() {
                @Override
                public String getName() {
                    return "capture";
                }

                @Override
                public String getModel() {
                    return "capture-model";
                }

                @Override
                public LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature) {
                    systemPrompts.add(system);
                    userPrompts.add(user);
                    return new LlmResponse("{\"document_type\":\"DRC-01\",\"law\":\"GST\",\"parse_confidence\":0.9,\"fields_needing_review\":[]}",
                            "capture-model", "capture", 500, 40, "end_turn", 0, 0, null);
                }
            };
        }
    }

    @Test
    void privacySettingsAndTheNonLocalOcrGuard() {
        assertEquals("local", PdfNoticeSteps.ocrProviderName(null));
        assertEquals("local", PdfNoticeSteps.ocrProviderName(" "));
        assertEquals("gemini", PdfNoticeSteps.ocrProviderName(" Gemini "));
        assertTrue(PdfNoticeSteps.anonymise(null), "NOTICE_ANONYMISE defaults to on");
        assertTrue(PdfNoticeSteps.anonymise("on"));
        assertFalse(PdfNoticeSteps.anonymise("off"));
        assertFalse(PdfNoticeSteps.anonymise("FALSE"));
        assertNull(PdfNoticeSteps.privacyRefusal("local", true));
        assertNull(PdfNoticeSteps.privacyRefusal("local", false));
        String refusal = PdfNoticeSteps.privacyRefusal("gemini", true);
        assertNotNull(refusal, "a non-local OCR provider with the anonymiser on is refused");
        assertTrue(refusal.startsWith("REFUSED: OCR_PROVIDER=gemini"), refusal);
        assertTrue(refusal.contains("NOTICE_ANONYMISE=off"), refusal);
        assertNotNull(PdfNoticeSteps.privacyRefusal("google_doc_ai", true));
        assertNull(PdfNoticeSteps.privacyRefusal("gemini", false), "switched off explicitly: the operator accepted it");
        assertNull(PdfNoticeSteps.privateResultsDir(null));
        assertNull(PdfNoticeSteps.privateResultsDir(" "));
        File results = PdfNoticeSteps.privateResultsDir(tmp.resolve("private").toString());
        assertEquals(tmp.resolve("private").resolve("results").toFile(), results);
        assertTrue(results.isDirectory());
    }

    @Test
    void theMappingIsNeverWrittenInsideAGitWorkingTree() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo/.git")).getParent();
        Path inside = Files.createDirectories(repo.resolve("testbench/results"));
        assertTrue(PdfNoticeSteps.insideGitWorkTree(inside.toFile()));
        assertFalse(PdfNoticeSteps.insideGitWorkTree(Files.createDirectories(tmp.resolve("private/results")).toFile()));
        PdfNoticeSteps.AnonymisedNotice anon = PdfNoticeSteps.anonymise("GSTIN: " + TAXPAYER_GSTIN, true);
        IOException e = assertThrows(IOException.class, () -> PdfNoticeSteps.saveAnonymised(inside.toFile(), "n", anon, om));
        assertTrue(e.getMessage().startsWith("REFUSED"), e.getMessage());
        assertEquals(0, Files.list(inside).count(), "nothing written");
    }

    @Test
    void localTextAnonymiseTriagePipelineAndRestoredOutputs() throws Exception {
        AppProperties props = TestAppProperties.fromApplicationYml();
        Path privateDir = tmp.resolve("private");
        props.setPrivateSeedDir(privateDir.toString());
        File resultsDir = PdfNoticeSteps.privateResultsDir(privateDir.toString());
        ApiUsageLogService usage = new ApiUsageLogService(null, props);
        RunCostLedger ledger = RunCostLedger.attach(usage, System.out);
        UUID tenant = V7LiveRig.TENANT;
        String noticeId = PdfNoticeSteps.noticeIdFor("DRC-01 Zeta.pdf");
        UUID noticeUuid = V7LiveRig.noticeUuid(noticeId);
        byte[] bytes = pdf(List.of(PAGE_1, PAGE_2));

        // (1) local text: PDFBox, no network, logged as step "ocr" at cost 0
        PdfNoticeSteps.OcrStep ocr = PdfNoticeSteps.ocr(new LocalOcrProvider(), bytes, "DRC-01 Zeta.pdf", "application/pdf", usage, tenant, noticeUuid);
        assertEquals(2, ocr.result().pageCount());
        assertTrue(ocr.result().text().contains("GSTIN: " + TAXPAYER_GSTIN), ocr.result().text());
        assertEquals(BigDecimal.ZERO, ocr.costInr());
        assertEquals(1, ledger.size());
        assertEquals("ocr", ledger.calls().get(0).step());
        String ocrLine = PdfNoticeSteps.ocrLine(ocr);
        assertTrue(ocrLine.startsWith("OCR: provider=local model=pdfbox pages=2 chars="), ocrLine);
        assertTrue(ocrLine.endsWith("tokens in=0 out=0 cost INR 0.0000"), ocrLine);

        // (2) anonymise and save under the private results folder
        PdfNoticeSteps.AnonymisedNotice anon = PdfNoticeSteps.anonymise(ocr.result().text(), true);
        assertTrue(anon.on());
        String a = anon.text();
        assertFalse(a.contains("ZZZZZ") || a.contains("YYYYY") || a.toLowerCase().contains("zeta alloys")
                || a.contains("Alpha Traders") || a.contains("Rakesh") || a.contains("Plot No. 12"), a);
        assertTrue(a.contains("GSTIN: 27AAAAA0001A1Z1"), a);
        assertTrue(a.contains("M/s NOTICEE COMPANY PRIVATE LIMITED"), a);
        assertTrue(a.contains("Section 73") && a.contains("FY 2019-20") && a.contains("Rs. 10,000") && a.contains("Section 17(5)"), a);
        String privacy = anon.privacyLine();
        assertTrue(privacy.startsWith("Privacy: anonymised GSTINs 2, PANs 0, names 3 (taxpayer 1, other parties 1, persons 1), addresses 1, references 1"), privacy);
        File[] saved = PdfNoticeSteps.saveAnonymised(resultsDir, noticeId, anon, om);
        assertEquals("drc-01-zeta_ocr_anon.txt", saved[0].getName());
        assertEquals("drc-01-zeta_anon_map.json", saved[1].getName());
        assertEquals(a, Files.readString(saved[0].toPath(), StandardCharsets.UTF_8));
        AnonymisationMap fromDisk = AnonymisationMap.readFrom(saved[1], om);
        assertEquals(TAXPAYER_GSTIN, fromDisk.valueOf("27AAAAA0001A1Z1"));
        assertEquals(ocr.result().text(), anon.restore(a), "the mapping restores the local text exactly");

        // (3) triage sees the anonymised text only
        AppProperties triageProps = new AppProperties();
        triageProps.getLlm().setProviderPrimary("anthropic");
        CapturingFactory factory = new CapturingFactory(triageProps);
        DocumentParsingAgent parsing = new DocumentParsingAgent(factory, new IdentityService(null), triageProps, om);
        PdfNoticeSteps.TriageStep triage = PdfNoticeSteps.triage(parsing, noticeId, "DRC-01 Zeta.pdf", ocr.result(), a, usage, tenant, noticeUuid);
        assertEquals("DRC-01", triage.documentType());
        assertEquals(1, factory.userPrompts.size());
        String prompt = factory.userPrompts.get(0);
        assertTrue(prompt.contains("27AAAAA0001A1Z1") && prompt.contains("NOTICEE COMPANY"), prompt);
        assertTrue(prompt.contains("notice.pdf") && !prompt.contains("DRC-01 Zeta.pdf"), "the file name (often the client's name) is not sent\n" + prompt);
        assertEquals("notice.pdf", PdfNoticeSteps.neutralFilename("Zeta Alloys SCN.PDF"));
        assertEquals("notice.png", PdfNoticeSteps.neutralFilename("scan.png"));
        assertEquals("notice.pdf", PdfNoticeSteps.neutralFilename(null));
        assertFalse(prompt.contains(TAXPAYER_GSTIN) || prompt.contains(SUPPLIER_GSTIN) || prompt.toLowerCase().contains("zeta") || prompt.contains("Rakesh"),
                "no real identifier reaches the triage model");
        assertFalse(factory.systemPrompts.get(0).toLowerCase().contains("zeta"));
        assertEquals(2, ledger.size(), "the triage call is billed through the usage log");
        assertEquals("triage", ledger.calls().get(1).step());

        // (4) the v7 pipeline on the anonymised text: the learned library stores the anonymised text only
        LearnedLibrary library = new LearnedLibrary(props, om);
        library.setSourceRun("0123456789abcdef");
        V7DraftingPipelineService pipeline = mockedPipeline(props);
        pipeline.setLearnedLibrary(library);
        BenchNoticeHeader.Header header = BenchNoticeHeader.parse(a);
        assertEquals("M/s NOTICEE COMPANY PRIVATE LIMITED", header.clientName());
        assertEquals("27AAAAA0001A1Z1", header.gstin());
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("notice_id", noticeId);
        notice.put("document_type", triage.documentType());
        DraftingInput input = new DraftingInput(noticeUuid, "formal", null, header.clientName(), null, null, "Regular", header.gstin(),
                "Maharashtra", "CGST Act", header.financialYear(), null, notice, Map.of(), a, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null);
        DraftingPipelineResult r = pipeline.runPipeline(input, tenant, noticeUuid);
        assertEquals(1, V7LiveRig.learnedLines(r).size());
        Path learnedDir = privateDir.resolve("learned");
        List<Path> learned = Files.list(learnedDir).toList();
        assertEquals(1, learned.size());
        String learnedJson = Files.readString(learned.get(0), StandardCharsets.UTF_8);
        // the notice id is the PDF's file name (so the partner can tie the entry to the PDF; name PDFs neutrally):
        // every other field holds the anonymised text only
        String withoutId = learnedJson.replaceAll("\"notice_id\" : \"[^\"]*\"", "\"notice_id\" : \"-\"");
        assertFalse(withoutId.contains(TAXPAYER_GSTIN) || withoutId.toLowerCase().contains("zeta") || withoutId.contains("Rakesh"),
                "the learned entry holds the anonymised text only:\n" + learnedJson);
        assertTrue(learnedJson.contains("27AAAAA0001A1Z1"), learnedJson);
        assertTrue(learnedJson.contains("\"client_name\" : \"M/s NOTICEE COMPANY PRIVATE LIMITED\""), learnedJson);

        // (5) outputs: anonymised as produced, restored for the partner
        String anonDraft = V7LivePipelineRunnerTest.renderDraft(noticeId, r);
        String restored = anon.restore(anonDraft);
        assertFalse(anonDraft.contains(TAXPAYER_GSTIN) || anonDraft.toLowerCase().contains("zeta alloys"), anonDraft);
        assertTrue(restored.contains(TAXPAYER_GSTIN), restored);
        assertTrue(restored.contains("ZETA ALLOYS PRIVATE LIMITED") || restored.contains("Zeta Alloys Private Limited"), restored);
        assertFalse(restored.contains("27AAAAA0001A1Z1") || restored.contains("NOTICEE COMPANY") || restored.contains("Noticee Company"), restored);
        byte[] docx = V7LiveRig.restoredDocx(r, anon.restorer(), anon.restore(header.clientName()), anon.restore(header.gstin()),
                triage.documentType(), header.financialYear());
        StringBuilder docxText = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            for (XWPFParagraph p : doc.getParagraphs()) docxText.append(p.getText()).append('\n');
        }
        assertTrue(docxText.toString().contains("M/s ZETA ALLOYS PRIVATE LIMITED · PAN: ZZZZZ9999Z"), docxText.toString());
        assertTrue(docxText.toString().contains("GSTIN: " + TAXPAYER_GSTIN), docxText.toString());
        assertFalse(docxText.toString().contains("AAAAA0001A"), docxText.toString());
        // the anonymiser off: identity, no mapping, no map file
        PdfNoticeSteps.AnonymisedNotice off = PdfNoticeSteps.anonymise(ocr.result().text(), false);
        assertEquals(ocr.result().text(), off.text());
        assertNull(off.restorer());
        assertTrue(off.privacyLine().contains("NOTICE_ANONYMISE=off"));
        assertNull(PdfNoticeSteps.saveAnonymised(resultsDir, noticeId + "-off", off, om)[1]);
    }

    /**
     * The self-check after anonymisation in the runner: residual 0 in the Privacy line and no flag for a clean text;
     * with something left (built here from a synthetic "anonymised" text), the Privacy line counts it, the warning
     * names shapes only, the warn flag {@code warn:anonymiser_residual} is set on the result, and strict mode
     * (default on) stops the notice before a non-Anthropic provider sees the text while Anthropic calls may proceed.
     */
    @Test
    void residualSelfCheckFlagsAndStrictModeRefusal() {
        assertTrue(PdfNoticeSteps.strict(null), "NOTICE_ANONYMISE_STRICT defaults to on");
        assertTrue(PdfNoticeSteps.strict("true"));
        assertFalse(PdfNoticeSteps.strict("off"));
        assertFalse(PdfNoticeSteps.strict("FALSE"));

        PdfNoticeSteps.AnonymisedNotice clean = PdfNoticeSteps.anonymise(String.join("\n", PAGE_1) + "\n" + String.join("\n", PAGE_2), true);
        assertEquals(0, clean.residualCount());
        assertTrue(clean.privacyLine().endsWith(", residual: 0"), clean.privacyLine());
        assertNull(clean.residualFlag());
        assertNull(clean.residualWarning("n"));
        assertNull(PdfNoticeSteps.residualRefusal(clean, true, "gemini", "anthropic"), "nothing left: gemini triage may run");

        String leftover = "supplier KAPPA WIRELESS NETWORKS, GSTIN 24YYYYY8888Y1Z8, PAN ZZZZZ9999Z, mobile 9876543210";
        NoticeAnonymiser.Residual residual = NoticeAnonymiser.residualRisks(leftover);
        assertEquals(4, residual.total(), residual.line());
        PdfNoticeSteps.AnonymisedNotice dirty = new PdfNoticeSteps.AnonymisedNotice(leftover, new AnonymisationMap(), true, residual);
        assertEquals(4, dirty.residualCount());
        assertTrue(dirty.privacyLine().endsWith(", residual: 4 (caps-phrase-3-words, gstin, pan, phone-10-digits)"), dirty.privacyLine());
        String warning = dirty.residualWarning("scn-001");
        assertNotNull(warning);
        assertTrue(warning.startsWith("WARNING: anonymiser residual: 4 (caps-phrase-3-words, gstin, pan, phone-10-digits) for scn-001"), warning);
        assertTrue(warning.contains(PdfNoticeSteps.FLAG_ANONYMISER_RESIDUAL), warning);
        for (String value : List.of("KAPPA", "YYYYY", "ZZZZZ", "9876543210")) {
            assertFalse(warning.contains(value) || dirty.privacyLine().contains(value), "a value leaked: " + value);
        }
        DraftCheckResult flag = dirty.residualFlag();
        assertNotNull(flag);
        assertEquals(PdfNoticeSteps.CHECK_ANONYMISER_RESIDUAL, flag.checkName());
        assertEquals("warn", flag.severity());
        assertFalse(flag.passed());
        assertEquals(PdfNoticeSteps.FLAG_ANONYMISER_RESIDUAL, flag.details().get("flag"));
        assertEquals(4, flag.details().get("residual"));
        assertFalse(flag.details().toString().contains("KAPPA") || flag.details().toString().contains("YYYYY"), flag.details().toString());
        // the flag travels with the pipeline result and shows in the Flags line
        DraftingPipelineResult r = new DraftingPipelineResult(null, List.of(), Map.of(), 0, 0, 0, 0, 0, "ok", List.of(), List.of());
        DraftingPipelineResult withFlag = V7LiveRig.withExtraChecks(r, List.of(flag));
        assertEquals(List.of(flag), withFlag.checkResults());
        assertEquals(List.of("FLAG_ANONYMISER_RESIDUAL:warn"), V7LivePipelineRunnerTest.flagList(withFlag.checkResults()));
        // strict mode: a non-Anthropic provider that would see the text stops the notice; Anthropic may proceed
        String refusal = PdfNoticeSteps.residualRefusal(dirty, true, "gemini", "anthropic");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("STOPPED: the anonymiser's self-check found residual: 4 ("), refusal);
        assertTrue(refusal.contains("gemini") && refusal.contains("LLM_PROVIDER_TRIAGE") && refusal.contains("NOTICE_ANONYMISE_STRICT=off"), refusal);
        assertFalse(refusal.contains("KAPPA") || refusal.contains("YYYYY"), refusal);
        assertNotNull(PdfNoticeSteps.residualRefusal(dirty, true, "anthropic", "gemini"), "a non-Anthropic primary provider is refused too");
        assertNull(PdfNoticeSteps.residualRefusal(dirty, true, "anthropic", "anthropic"), "Anthropic calls may proceed (the warn flag is set)");
        assertNull(PdfNoticeSteps.residualRefusal(dirty, true, "Anthropic", null));
        assertNull(PdfNoticeSteps.residualRefusal(dirty, false, "gemini", "anthropic"), "NOTICE_ANONYMISE_STRICT=off accepts it explicitly");
        PdfNoticeSteps.AnonymisedNotice off = PdfNoticeSteps.anonymise(leftover, false);
        assertEquals(0, off.residualCount());
        assertNull(PdfNoticeSteps.residualRefusal(off, true, "gemini", "anthropic"), "the anonymiser off is the operator's explicit decision");
    }

    @Test
    void triageProviderIsConfigurablePerAgent() {
        AppProperties p = new AppProperties();
        p.getLlm().setProviderPrimary("anthropic");
        assertEquals("anthropic", p.getLlm().providerForAgent("parsing"), "default: the primary provider, not gemini");
        p.getLlm().setProviderParsing("gemini");
        assertEquals("gemini", p.getLlm().providerForAgent("parsing"));
        assertEquals("anthropic", p.getLlm().providerForAgent("matching"), "other agents keep the primary provider");
        // with gemini, an Anthropic model id configured for parsing is not sent; the gemini model is
        p.getLlm().setModelParsing("claude-test-model");
        p.getLlm().getGemini().setModel("gemini-test-model");
        assertEquals("gemini-test-model", p.modelForAgent("parsing", "gemini"));
        p.getLlm().setModelParsing("gemini-other");
        assertEquals("gemini-other", p.modelForAgent("parsing", "gemini"));
        AppProperties yml = TestAppProperties.fromApplicationYml();
        assertEquals("", yml.getLlm().getProviderParsing(), "application.yml: LLM_PROVIDER_TRIAGE unset = primary provider");
        assertTrue(yml.getLlm().getModelParsing().startsWith("claude"), "application.yml: the parsing model defaults to an Anthropic model id");
    }

    private V7DraftingPipelineService mockedPipeline(AppProperties props) {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer.</p>", "15", "<p>Checklist.</p>"), null, null, null));
        IssueMatchingAgent matching = mock(IssueMatchingAgent.class);
        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);
        MatchedNoticeInfo info = new MatchedNoticeInfo("ZD000000000001X", null, "24-05-2024", null, "2019-20", "2019-20", 10000.0, "Officer");
        when(matching.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(new MatchingResult(info,
                List.of(new MatchedIssue(1, "none", List.of(), "ITC on hire charges from Supplier 1 Traders denied", List.of(),
                        Map.of("period", "2019-20", "amount", 10000.0, "legal_section", "Section 17(5)"), List.of("1"), List.of())),
                Map.of("issue", List.of(1)), List.of(), List.of(), "h", 1, 1, 0, 0));
        when(newIssue.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(1, "<p>NOTICEE COMPANY PRIVATE LIMITED (GSTIN 27AAAAA0001A1Z1) received the goods from Supplier 1 Traders.</p>", null,
                        "<p>Section 16(2) conditions are met.</p>", List.of(), null, List.of("Hire agreements"), "Summary.",
                        "test-model-new", "new_issue_v2"));
        return new V7DraftingPipelineService(matching, null, om, fill, assembly, new DraftCheckService(null),
                null, newIssue, mock(HighStakesDraftingAgent.class), null, null, props, null);
    }
}
