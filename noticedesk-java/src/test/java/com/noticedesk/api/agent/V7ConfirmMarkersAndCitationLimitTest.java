package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.DocxExportService;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.V7DraftingPipelineService;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.DraftTextCleanup;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fixes 2 and 4 of the RFD-08 run, proved offline: a writer answer that assumes a fact writes it as a
 * [[CONFIRM: ...]] marker and lists it in {@code assumed_facts} (parsed by the real agent); the pipeline then produces
 * the CHECK_ASSUMED_FACTS row, counts the marker in CHECK_MARKERS_LIST, lists it in the Section 13 partner note under
 * "Facts to confirm with the client", keeps the marker in the text and prints it in bold in the Word export. A writer
 * citing more cases than {@code matching.citations.max-per-issue} has the extra ones kept in the text, NOT_CHECKED
 * ("beyond per-issue citation limit") and never sent to IndianKanoon. Synthetic text, mocked IndianKanoon client, no
 * model call.
 */
class V7ConfirmMarkersAndCitationLimitTest {

    private static final String TOKEN = "test-token-not-real";
    private static final String FACT = "Supplier A filed its GSTR-1 for JAN-2026 after the due date";
    private static final String CONFIRM_SENTENCE = "if, as the Noticee will confirm, [[CONFIRM: " + FACT + "]], the difference is a timing difference";

    /** The new-issue writer's answer, as the model returns it (five cases: two beyond the default limit of three). */
    private static final String NEW_ISSUE_JSON = """
            {
              "section04_html": "<p>The notice records that the supplier's credit has been rejected in GSTR-2B; %s and the credit is eligible. See Case One v. Union of India, Case Two v. State of Gujarat, Case Three v. Commissioner of CGST, Case Four v. State Tax Officer and Case Five v. Union of India.</p>",
              "section05_html": "<li><strong>Para 2:</strong> Denied.</li>",
              "section06_html": "<p>Legal submission on timing differences.</p>",
              "citations": [
                {"case": "Case One v. Union of India", "court": "Supreme Court", "year": 2020, "quoted_text": null, "cited_for": "a"},
                {"case": "Case Two v. State of Gujarat", "court": "Supreme Court", "year": 2020, "quoted_text": null, "cited_for": "b"},
                {"case": "Case Three v. Commissioner of CGST", "court": "Supreme Court", "year": 2020, "quoted_text": null, "cited_for": "c"},
                {"case": "Case Four v. State Tax Officer", "court": "Supreme Court", "year": 2020, "quoted_text": null, "cited_for": "d"},
                {"case": "Case Five v. Union of India", "court": "Supreme Court", "year": 2020, "quoted_text": null, "cited_for": "e"}
              ],
              "documents": ["Supplier A's GSTR-1 filing acknowledgement for JAN-2026"],
              "summary_line": "The difference is a timing difference.",
              "strength_note": "Depends on the supplier's filing date.",
              "assumed_facts": [{"fact": "%s", "sentence": "%s"}]
            }
            """.formatted(CONFIRM_SENTENCE, FACT, CONFIRM_SENTENCE);

    private IssueMatchingAgent matchingAgent;
    private NewIssueDraftingAgent newIssueAgent;
    private PartialDraftingAgent partialAgent;
    private IndianKanoonClient ikClient;
    private final ObjectMapper om = new ObjectMapper();
    private TemplateFillService fill;
    private ReplyAssemblyService assembly;
    private AppProperties props;
    private NewIssueDraftingResult parsedNewIssue;

    @BeforeEach
    void setUp() throws Exception {
        fill = new TemplateFillService(null, null, om);
        assembly = new ReplyAssemblyService(null, null, om);
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer under {{notice.section}}.</p>", "15", "<p>Checklist.</p>"), null, null, null));
        matchingAgent = mock(IssueMatchingAgent.class);
        newIssueAgent = mock(NewIssueDraftingAgent.class);
        partialAgent = mock(PartialDraftingAgent.class);
        ikClient = mock(IndianKanoonClient.class);
        props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken(TOKEN);

        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/2", "DIN-2", "2026-02-20", "within 15 days", "2025-26", "JAN-2026", 150000.0, "Officer");
        MatchingResult mr = new MatchingResult(info, List.of(
                new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of("period", "JAN-2026", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("2"), List.of()),
                new MatchedIssue(2, "none", List.of(), "w", List.of(), Map.of("period", "JAN-2026", "amount", 50000.0, "legal_section", "Section 16(2)(c)"), List.of("3"), List.of())),
                Map.of("issue", List.of(2, 3)), List.of(), List.of(), "h", 1, 1, 0, 0);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(mr);

        // the real parser reads the writer's answer: the CONFIRM marker and the assumed fact come through it
        parsedNewIssue = new NewIssueDraftingAgent(props, om, null, null).parseOrThrow(NEW_ISSUE_JSON, 1, "test-model-new");
        NewIssueDraftingResult second = new NewIssueDraftingResult(2, "<p>Second issue ground citing Case Four v. State Tax Officer and Case Six v. Commissioner of GST.</p>",
                "<li><strong>Para 3:</strong> Denied.</li>", "<p>Second issue law.</p>",
                List.of(new RawAiCitation("Case Four v. State Tax Officer", "Supreme Court", 2020, null, "d"),
                        new RawAiCitation("Case Six v. Commissioner of GST", "Supreme Court", 2020, null, "f")),
                "Fine.", List.of(), "Second summary.", "test-model-new", "new_issue_v2");
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((MatchedIssue) inv.getArgument(0)).issueNo() == 1 ? parsedNewIssue : second);

        when(ikClient.search(eq(TOKEN), anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(1);
            String[] sides = q.split(" v\\. ");
            return List.of(new IkDoc("9", sides[0] + " vs " + sides[1] + " on 1 January, 2020", "Supreme Court of India", "2020-01-01"));
        });
    }

    private V7DraftingPipelineService pipeline() {
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, null, null, ikClient);
        return new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly, new DraftCheckService(null),
                partialAgent, newIssueAgent, null, citationAgent, props, new ApiUsageLogService(null, props));
    }

    private static DraftingInput input() {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Orbit Lamps Private Limited", null, null, null,
                "27AAAAA0000A1Z5", "Maharashtra", "CGST Act", "2025-26", null,
                Map.of("document_type", "scn_73", "section", "Section 73"), Map.of(),
                "Show cause notice under Section 73.\n1. Intro.\n2. The supplier's ITC has been rejected in GSTR-2B.\n3. Other ground.",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static String section(DraftingPipelineResult r, int num) {
        return r.draft().sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    private static DraftCheckResult check(DraftingPipelineResult r, String name) {
        return r.checkResults().stream().filter(c -> c.checkName().equals(name)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- fix 2: assumed facts and CONFIRM markers

    @Test
    void theWriterAnswerIsParsedWithItsAssumedFacts_andTheMarkerSurvivesSanitising() {
        assertEquals(1, parsedNewIssue.assumedFacts().size());
        assertEquals(FACT, parsedNewIssue.assumedFacts().get(0).fact());
        assertEquals(CONFIRM_SENTENCE, parsedNewIssue.assumedFacts().get(0).sentence());
        assertTrue(parsedNewIssue.section04Html().contains("[[CONFIRM: " + FACT + "]]"), parsedNewIssue.section04Html());
        assertEquals(5, parsedNewIssue.citations().size());

        // the partial writer's answer is read the same way; a plain string is a fact without its sentence
        PartialDraftingResult partial = new PartialDraftingAgent(props, om, null, null).parseOrThrow("""
                {"sections": [{"section": 4, "after_block": null, "html": "<p>If [[CONFIRM: the goods were received in JAN-2026]], the credit is in time.</p>"}],
                 "citations": [], "documents": [], "summary_line": "s",
                 "assumed_facts": ["[[CONFIRM: the goods were received in JAN-2026]]"]}
                """, "m");
        assertEquals(List.of(new AssumedFact("the goods were received in JAN-2026", null)), partial.assumedFacts());
        // an answer without the key (older writers) is still usable
        assertTrue(new PartialDraftingAgent(props, om, null, null).parseOrThrow(
                "{\"sections\": [{\"section\": 4, \"after_block\": null, \"html\": \"<p>x</p>\"}]}", "m").assumedFacts().isEmpty());
    }

    @Test
    void confirmMarkerProducesTheCheckRow_theMarkerCount_andThePartnerNoteEntry() {
        DraftingPipelineResult r = pipeline().runPipeline(input());

        // the marker is in the client-facing text, unchanged (cleanup keeps it like a MISSING marker)
        String s04 = section(r, 4);
        assertTrue(s04.contains("if, as the Noticee will confirm, [[CONFIRM: " + FACT + "]], the difference"), s04);

        DraftCheckResult assumed = check(r, DraftCheckService.CHECK_ASSUMED_FACTS);
        assertEquals("warn", assumed.severity());
        assertFalse(assumed.passed());
        assertEquals(List.of("Issue #1: " + FACT), assumed.details().get("assumed_facts"));
        assertEquals(List.of(FACT), assumed.details().get("confirm_markers"));
        assertEquals(1, assumed.details().get("confirm_count"));

        DraftCheckResult markers = check(r, "CHECK_MARKERS_LIST");
        assertEquals(1, markers.details().get("confirm_count"));
        assertEquals(List.of(FACT), markers.details().get("confirm_markers"));
        assertFalse(markers.passed(), "a CONFIRM marker blocks the draft like a MISSING marker");
        assertTrue(((List<?>) check(r, "CHECK_NO_UNRESOLVED_PLACEHOLDERS").details().get("stray_markers")).isEmpty(), "CONFIRM is a known marker");

        String s13 = section(r, 13);
        assertTrue(s13.contains("<p><strong>Facts to confirm with the client:</strong></p><ul><li>" + FACT + " (severity: warn)</li></ul>"), s13);
        assertTrue(s13.contains("Issue #1 fact to confirm with the client: " + FACT + " (supports: " + CONFIRM_SENTENCE + ")"), s13);
        // the fact is stored with the issue's AI output
        Object facts = r.aiIssueSections().stream().filter(x -> x.issueNo() == 1).findFirst().orElseThrow().outputJson().get("assumed_facts");
        assertEquals(List.of(Map.of("fact", FACT, "sentence", CONFIRM_SENTENCE)), facts);
        // no fact assumed for issue 2: its note lines carry none
        assertFalse(s13.contains("Issue #2 fact to confirm"));
    }

    @Test
    void noAssumedFactsAndNoMarker_passesTheCheck() {
        doReturn(new NewIssueDraftingResult(1, "<p>Plain ground.</p>", "<li><strong>Para 2:</strong> Denied.</li>", "<p>Law.</p>", List.of(), null))
                .when(newIssueAgent).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
        DraftingPipelineResult r = pipeline().runPipeline(input());
        DraftCheckResult assumed = check(r, DraftCheckService.CHECK_ASSUMED_FACTS);
        assertTrue(assumed.passed());
        assertTrue(section(r, 13).contains("<p><strong>Facts to confirm with the client:</strong> None</p>"));
        assertEquals(0, check(r, "CHECK_MARKERS_LIST").details().get("confirm_count"));
    }

    @Test
    void confirmMarkerIsBoldInTheWordExport_andCleanupTreatsItLikeMissing() throws Exception {
        DraftingPipelineResult r = pipeline().runPipeline(input());
        List<Map<String, Object>> sections = new ArrayList<>();
        for (DraftSection s : r.draft().sections()) sections.add(Map.of("num", s.num(), "title", s.title(), "body_html", s.bodyHtml()));
        byte[] docx = new DocxExportService().renderDraftDocx("filing",
                new DocxExportService.CoverSheetData("Firm", "Orbit Lamps Private Limited", "AAAAA0000A", "GSTIN", "27AAAAA0000A1Z5", "SCN", "2025-26", null, null),
                sections, null);
        List<XWPFRun> markerRuns = new ArrayList<>();
        List<XWPFRun> plainRuns = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                for (XWPFRun run : p.getRuns()) {
                    if (run.text().startsWith("[[CONFIRM: " + FACT)) markerRuns.add(run);
                    else if (run.text().contains("the difference is a timing difference")) plainRuns.add(run);
                }
            }
        }
        assertFalse(markerRuns.isEmpty(), "the CONFIRM marker is its own run");
        assertTrue(markerRuns.stream().allMatch(XWPFRun::isBold), "the marker is bold");
        assertFalse(plainRuns.isEmpty());
        assertTrue(plainRuns.stream().noneMatch(XWPFRun::isBold), "the sentence around it is not");

        assertEquals("[[CONFIRM: supplier_gstr1_filed_late]] and [[MISSING: client_gstin]]",
                DraftTextCleanup.stripMarkerPrefixes("[[CONFIRM: issue.supplier_gstr1_filed_late]] and [[MISSING: client.gstin]]"));
        assertEquals(List.of(FACT), DraftTextCleanup.dedupeMarkers(List.of("[[CONFIRM: " + FACT + "]]", FACT, " [[CONFIRM: " + FACT.toLowerCase(Locale.ROOT) + "]]")));
        assertEquals(List.of(FACT, "the goods were received"), DraftTextCleanup.confirmMarkers(
                "<p>If [[CONFIRM: " + FACT + "]] then; and [[CONFIRM:  the goods  were received ]]. [[MISSING: x]]</p>"));
    }

    // ---------------------------------------------------------------- fix 4: per-issue citation limit

    @Test
    void citationsBeyondThePerIssueLimitAreKeptInTheText_notChecked_andNeverSentToIndianKanoon() throws Exception {
        DraftingPipelineResult r = pipeline().runPipeline(input());   // default limit 3

        Map<String, VerifiedCitation> byName = r.citations().stream().collect(Collectors.toMap(VerifiedCitation::caseName, c -> c, (a, b) -> a));
        for (String checked : List.of("Case One v. Union of India", "Case Two v. State of Gujarat", "Case Three v. Commissioner of CGST",
                "Case Four v. State Tax Officer", "Case Six v. Commissioner of GST")) {
            assertEquals("VERIFIED", byName.get(checked).status(), checked);
            verify(ikClient).search(TOKEN, checked);
        }
        VerifiedCitation held = byName.get("Case Five v. Union of India");
        assertEquals("NOT_CHECKED", held.status());
        assertEquals(V7DraftingPipelineService.BEYOND_CITATION_LIMIT, held.details());
        verify(ikClient, never()).search(eq(TOKEN), eq("Case Five v. Union of India"));
        verify(ikClient, times(5)).search(eq(TOKEN), anyString());
        assertEquals(6, r.citations().size(), "Case Four is cited by both issues: once within issue 2's limit, checked once");

        assertTrue(section(r, 4).contains("Case Five v. Union of India"), "the extra citation stays in the text");
        String s13 = section(r, 13);
        assertTrue(s13.contains("Issue #1: 2 citation(s) beyond the per-issue limit of 3 not sent to IndianKanoon (kept in the text, NOT_CHECKED): "
                + "Case Four v. State Tax Officer; Case Five v. Union of India"), s13);
        assertTrue(s13.contains("Citation Case Five v. Union of India (Supreme Court, 2020): NOT_CHECKED - beyond per-issue citation limit"), s13);
        assertFalse(check(r, "CHECK_AI_CITATIONS_VERIFIED").passed(), "an unchecked citation still blocks until the partner resolves it");
        assertEquals("info", check(r, "FLAG_CITATION_LIMIT_APPLIED").severity());
    }

    @Test
    void aHigherOrZeroLimitChecksEveryCitation() throws Exception {
        props.getMatching().getCitations().setMaxPerIssue(5);
        DraftingPipelineResult r = pipeline().runPipeline(input());
        assertTrue(r.citations().stream().allMatch(c -> "VERIFIED".equals(c.status())), r.citations().toString());
        verify(ikClient, times(6)).search(eq(TOKEN), anyString());
        assertTrue(r.checkResults().stream().noneMatch(c -> c.checkName().equals("FLAG_CITATION_LIMIT_APPLIED")));

        reset(ikClient);
        when(ikClient.search(eq(TOKEN), anyString())).thenReturn(List.of());
        props.getMatching().getCitations().setMaxPerIssue(0);
        DraftingPipelineResult unlimited = pipeline().runPipeline(input());
        verify(ikClient, times(6)).search(eq(TOKEN), anyString());
        assertTrue(unlimited.citations().stream().allMatch(c -> "NOT_FOUND".equals(c.status())));
    }

    @Test
    void propertiesBindFromApplicationYml() {
        AppProperties fromYml = TestAppProperties.fromApplicationYml();
        if (System.getenv("CITATIONS_MAX_PER_ISSUE") == null) assertEquals(3, fromYml.getMatching().getCitations().getMaxPerIssue());
        if (System.getenv("SHORTLIST_TITLE_MAX_CARDS") == null) assertEquals(15, fromYml.getMatching().getShortlist().getTitleMaxCards());
    }
}
