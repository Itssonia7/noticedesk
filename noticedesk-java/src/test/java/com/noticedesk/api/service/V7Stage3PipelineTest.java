package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.HighStakesDraftingAgent.HighStakesResult;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.AiIssueSectionRecord;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Full v7 pipeline (Stage 1 -> 2 -> 3 -> checks) with every external call mocked:
 * matching agent, AI writers and the IndianKanoon HTTP client. No live API call is made.
 */
class V7Stage3PipelineTest {

    private static final String TOKEN = "test-token-not-real";

    private IssueMatchingAgent matchingAgent;
    private PartialDraftingAgent partialAgent;
    private NewIssueDraftingAgent newIssueAgent;
    private HighStakesDraftingAgent highStakesAgent;
    private IndianKanoonClient ikClient;
    private AppProperties props;
    private V7DraftingPipelineService pipeline;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper om = new ObjectMapper();
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        matchingAgent = mock(IssueMatchingAgent.class);
        partialAgent = mock(PartialDraftingAgent.class);
        newIssueAgent = mock(NewIssueDraftingAgent.class);
        highStakesAgent = mock(HighStakesDraftingAgent.class);
        ikClient = mock(IndianKanoonClient.class);

        props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken(TOKEN);
        props.getCitation().setMaxCallsPerDraft(15);
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, null, null, ikClient);

        pipeline = new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly, new DraftCheckService(null),
                partialAgent, newIssueAgent, highStakesAgent, citationAgent);

        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-A", 1, "CARD-A",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-A-04", 4, "<p>Template ground for {{client.legal_name}} on {{issue.amount_tax}}.</p>", List.of(), null),
                        new TemplateBlock("BLK-A-06", 6, "<p>Template legal submission A.</p>", List.of(), null)),
                List.of("client.legal_name", "issue.amount_tax"),
                List.of("Template document A"), "Template summary for {{issue.amount_tax}}.", List.of(), null, null, null));
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-B", 1, "CARD-B",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-B-04", 4, "<p>Template ground B that does not fit this notice.</p>", List.of(), null)),
                List.of(), List.of("Unrelated template document B"), "Template B summary.", List.of(), null, null, null));
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer: drop the proceedings under {{notice.section}}.</p>", "15", "<p>Checklist.</p>"),
                null, null, null));

        // Three issues: 1 full (template), 2 partial (card B), 3 none
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/1", "DIN-1", "01-04-2024", "within 30 days", "2022-23", "2022-23", 160000.0, "Officer");
        MatchingResult mr = new MatchingResult(info, List.of(
                new MatchedIssue(1, "full", List.of("CARD-A"), "w", List.of(), Map.of("period", "2022-23", "amount", 100000.0), List.of("2"), List.of()),
                new MatchedIssue(2, "partial", List.of("CARD-B"), "w", List.of(), Map.of("period", "2022-23", "amount", 50000.0), List.of("3"), List.of()),
                new MatchedIssue(3, "none", List.of(), "w", List.of(), Map.of("period", "2022-23", "amount", 10000.0), List.of("4"), List.of())),
                Map.of("record", List.of(1), "issue", List.of(2, 3, 4), "demand", List.of(5)),
                List.of(), List.of(), "h", 1, 1, 0, 0);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any())).thenReturn(mr);

        when(partialAgent.generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(new PartialDraftingResult(
                        List.of(new PartialSectionAddition(4, null, "<p>Partial ground relying on Alpha Traders v. State of Gujarat.<script>alert(1)</script></p>"),
                                new PartialSectionAddition(5, null, "<li><strong>Para 3:</strong> Denied as regards the partial issue.</li>"),
                                new PartialSectionAddition(6, null, "<p onclick=\"x\">Partial legal submission. See Ghost Co v. Union of India (2015) 1 XYZ 2 (SC).</p>")),
                        List.of(new RawAiCitation("Alpha Traders v. State of Gujarat", "Gujarat High Court", 2019, null, "point A"),
                                new RawAiCitation("Ghost Co v. Union of India", "Supreme Court", 2015, null, "point B")),
                        List.of("Partial issue document"), "Partial issue summary.", "test-model-partial", "partial_drafting_v2"));
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new NewIssueDraftingResult(3,
                        "<p>New issue ground. <a href=\"http://x\">link text</a></p>",
                        "<li><strong>Para 4:</strong> Denied as regards the new issue.</li><li><strong>Para 9:</strong> Para that does not exist.</li>",
                        "<p>New issue legal submission.</p>",
                        List.of(new RawAiCitation("Beta Ltd v. Commissioner of CGST", "Supreme Court", 2020, "burden is on the revenue", "point C")),
                        "Moderate strength; evidence needed.",
                        List.of("New issue document"), "New issue summary.", "test-model-new", "new_issue_v2"));

        when(ikClient.search(eq(TOKEN), eq("Alpha Traders v. State of Gujarat")))
                .thenReturn(List.of(new IkDoc("11", "Alpha Traders vs State Of Gujarat on 3 May, 2019", "Gujarat High Court", "2019-05-03")));
        when(ikClient.search(eq(TOKEN), eq("Ghost Co v. Union of India")))
                .thenReturn(List.of(new IkDoc("12", "Somebody Else vs Union Of India on 1 Jan, 2015", "Supreme Court of India", "2015-01-01")));
        when(ikClient.search(eq(TOKEN), eq("Beta Ltd v. Commissioner of CGST")))
                .thenReturn(List.of(new IkDoc("13", "Beta Ltd. vs Commissioner Of Cgst on 9 Sep, 2020", "Supreme Court of India", "2020-09-09")));
        when(ikClient.fragment(eq(TOKEN), eq("13"), anyString())).thenReturn("held that the <b>burden is on the revenue</b> to prove");
    }

    private DraftingInput input(String section) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Test Client Pvt Ltd", null, null, null,
                "TESTGSTIN", "Test State", "CGST Act", "2022-23", null,
                Map.of("document_type", "scn_73", "section", section), Map.of(), "notice text", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static String allHtml(DraftingPipelineResult r) {
        return r.draft().sections().stream().map(DraftSection::bodyHtml).collect(Collectors.joining("\n"));
    }

    private static String section(DraftingPipelineResult r, int num) {
        return r.draft().sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    private static DraftCheckResult check(DraftingPipelineResult r, String name) {
        return r.checkResults().stream().filter(c -> c.checkName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void stage3WritersFillEveryPendingMarkerAndOutputIsReturned() {
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        String all = allHtml(r);
        assertFalse(all.contains("PENDING_AI"), "no [[PENDING_AI]] may remain when the writers return text");
        assertEquals(15, r.draft().sections().size());

        String s04 = section(r, 4);
        assertTrue(s04.contains("Template ground for Test Client Pvt Ltd on Rs 1,00,000."));
        assertTrue(s04.contains("Partial ground relying on Alpha Traders v. State of Gujarat."));
        assertTrue(s04.contains("New issue ground."));
        assertFalse(s04.contains("Template ground B"), "partial template block not anchored by the writer must not be rendered");

        // Para-wise reply: AI paras used, non-existent Para 9 dropped, 5 paras only
        String s05 = section(r, 5);
        assertTrue(s05.contains("Denied as regards the partial issue."));
        assertTrue(s05.contains("Denied as regards the new issue."));
        assertFalse(s05.contains("Para 9"));
        assertFalse(s05.contains("Para 6:"));
        assertTrue(s05.contains("<strong>Para 2:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1."));

        // Sanitised AI HTML
        assertFalse(all.contains("<script"));
        assertFalse(all.contains("alert(1)"));
        assertFalse(all.contains("onclick"));
        assertFalse(all.contains("<a "));
        assertTrue(s04.contains("link text"));

        // Strength note only in Section 13
        for (DraftSection s : r.draft().sections()) {
            if (s.num() != 13) assertFalse(s.bodyHtml().contains("Moderate strength"), "strength note leaked into section " + s.num());
        }
        assertTrue(section(r, 13).contains("Moderate strength; evidence needed."));

        // Documents: template doc for the full issue, AI docs for partial/new; not the unused template's list
        String s10 = section(r, 10);
        assertTrue(s10.contains("Template document A"));
        assertTrue(s10.contains("Partial issue document"));
        assertTrue(s10.contains("New issue document"));
        assertFalse(s10.contains("Unrelated template document B"));
        assertFalse(s10.contains("GSTR-3B"));

        // ai_issue_sections output returned for the workflow to persist
        List<AiIssueSectionRecord> rows = r.aiIssueSections();
        assertEquals(2, rows.size());
        AiIssueSectionRecord newIssueRow = rows.stream().filter(x -> x.issueNo() == 3).findFirst().orElseThrow();
        assertEquals("test-model-new", newIssueRow.model());
        assertEquals("new_issue_v2", newIssueRow.promptVersion());
        assertEquals("new_issue", newIssueRow.outputJson().get("writer"));
        assertEquals("pending_review", newIssueRow.status());

        verify(partialAgent).generatePartialDrafting(argThat(i -> i.issueNo() == 2), any(), any(), eq("scn_73"), any(), eq(false));
        verify(newIssueAgent).generateNewIssueDraft(argThat(i -> i.issueNo() == 3), any(), eq("notice text"), eq("scn_73"), any(), eq(false));
        verify(highStakesAgent, never()).generateHighStakesDraft(any(), any(), any(), any());
    }

    @Test
    void aiCitationsAreVerifiedAndNotFoundOnesRemoved() throws Exception {
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        Map<String, String> status = new HashMap<>();
        for (VerifiedCitation c : r.citations()) status.put(c.caseName(), c.status());
        assertEquals("VERIFIED", status.get("Alpha Traders v. State of Gujarat"));
        assertEquals("NOT_FOUND", status.get("Ghost Co v. Union of India"));
        assertEquals("VERIFIED", status.get("Beta Ltd v. Commissioner of CGST"));   // quote found by fragment check
        verify(ikClient).fragment(TOKEN, "13", "burden is on the revenue");
        verify(ikClient, never()).fragment(eq(TOKEN), eq("11"), anyString());       // no quote -> no fragment call

        String s06 = section(r, 6);
        assertFalse(s06.contains("Ghost Co"), "NOT_FOUND citation text must be removed");
        assertTrue(s06.contains("Partial legal submission."), "the argument around a removed citation is kept");

        assertFalse(check(r, "CHECK_AI_CITATIONS_REMOVED").passed());
        assertEquals("warn", check(r, "CHECK_AI_CITATIONS_REMOVED").severity());
        assertTrue(check(r, "CHECK_AI_CITATIONS_VERIFIED").passed());
        assertEquals(3, r.citationSummary().get("total"));
    }

    @Test
    void missingIndianKanoonTokenDoesNotCrashAndRaisesBlockFlag() throws Exception {
        props.getCitation().setIndiankanoonApiToken("");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("INDIANKANOON_API_TOKEN") == null);

        DraftingPipelineResult r = assertDoesNotThrow(() -> pipeline.runPipeline(input("Section 73")));
        assertTrue(r.citations().stream().allMatch(c -> "NOT_CHECKED".equals(c.status())));
        DraftCheckResult cit = check(r, "CHECK_AI_CITATIONS_VERIFIED");
        assertFalse(cit.passed());
        assertEquals("block", cit.severity());
        verify(ikClient, never()).search(anyString(), anyString());
        assertTrue(section(r, 13).contains("INDIANKANOON_API_TOKEN is not set"));
    }

    @Test
    void callCapMarksRemainingCitationsNotChecked() throws Exception {
        props.getCitation().setMaxCallsPerDraft(1);
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));
        assertEquals(1, r.citations().stream().filter(c -> "VERIFIED".equals(c.status())).count());
        assertTrue(r.citations().stream().anyMatch(c -> "NOT_CHECKED".equals(c.status())));
        assertFalse(check(r, "CHECK_AI_CITATIONS_VERIFIED").passed());
        verify(ikClient, times(1)).search(anyString(), anyString());
    }

    @Test
    void writerFailureKeepsPendingMarkerAsBlockFlag() {
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean())).thenReturn(null);
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));
        assertTrue(section(r, 4).contains("[[PENDING_AI: Issue 3"));
        assertFalse(check(r, "CHECK_MARKERS_LIST").passed());
        assertTrue(section(r, 13).contains("new-issue writer returned no usable output"));
        assertEquals(1, r.aiIssueSections().size());
    }

    @Test
    void highStakesModeRoutesWritersAndWritesSections01And03() {
        when(highStakesAgent.isHighStakesActive(eq("Section 74"), anyString(), any())).thenReturn(true);
        when(highStakesAgent.generateHighStakesDraft(any(), any(), anyString(), any()))
                .thenReturn(new HighStakesResult(true, "<p>High-stakes summary narrative.</p>", "<p>High-stakes background narrative.</p>",
                        List.of(), "test-model-hs", "high_stakes_v1"));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 74"));

        String s01 = section(r, 1);
        assertTrue(s01.contains("High-stakes summary narrative."));
        assertTrue(s01.contains("Reply to Notice Ref REF/1"), "code-built facts stay verbatim in high-stakes mode");
        assertTrue(section(r, 3).contains("High-stakes background narrative."));
        assertTrue(section(r, 3).contains("TESTGSTIN"));
        assertTrue(section(r, 4).contains("Template ground for Test Client Pvt Ltd on Rs 1,00,000."), "template blocks kept verbatim");
        verify(partialAgent).generatePartialDrafting(any(), any(), any(), anyString(), any(), eq(true));
        verify(newIssueAgent).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), eq(true));
        assertTrue(section(r, 13).contains("High-stakes mode active"));
    }

    @Test
    void prayerAndNoticeUnderstandingUseTheNoticesOwnSection() {
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 171"));
        assertTrue(section(r, 2).contains("under Section 171 of the CGST Act, 2017"));
        assertTrue(section(r, 12).contains("drop the proceedings under Section 171"));
        assertFalse(section(r, 2).contains("Section 73"));
        assertFalse(section(r, 12).contains("Section 73"));
        // stage template scn_73 used for a Section 171 notice -> block flag
        DraftCheckResult c = check(r, "CHECK_PROCEEDING_CONSISTENT");
        assertFalse(c.passed());
        assertEquals("block", c.severity());
    }
}
