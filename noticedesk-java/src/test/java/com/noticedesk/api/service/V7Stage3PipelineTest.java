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
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFatalException;
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
    private final ObjectMapper om = new ObjectMapper();
    private TemplateFillService fill;
    private ReplyAssemblyService assembly;
    private CitationVerificationAgent citationAgent;

    @BeforeEach
    void setUp() throws Exception {
        fill = new TemplateFillService(null, null, om);
        assembly = new ReplyAssemblyService(null, null, om);
        matchingAgent = mock(IssueMatchingAgent.class);
        partialAgent = mock(PartialDraftingAgent.class);
        newIssueAgent = mock(NewIssueDraftingAgent.class);
        highStakesAgent = mock(HighStakesDraftingAgent.class);
        ikClient = mock(IndianKanoonClient.class);

        props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken(TOKEN);
        props.getCitation().setMaxCallsPerDraft(15);
        citationAgent = new CitationVerificationAgent(om, props, null, null, ikClient);

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
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(mr);

        when(partialAgent.generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new PartialDraftingResult(
                        List.of(new PartialSectionAddition(4, null, "<p>Partial ground relying on Alpha Traders v. State of Gujarat.<script>alert(1)</script></p>"),
                                new PartialSectionAddition(5, null, "<li><strong>Para 3:</strong> Denied as regards the partial issue.</li>"),
                                new PartialSectionAddition(6, null, "<p onclick=\"x\">Partial legal submission. See Ghost Co v. Union of India (2015) 1 XYZ 2 (SC).</p>")),
                        List.of(new RawAiCitation("Alpha Traders v. State of Gujarat", "Gujarat High Court", 2019, null, "point A"),
                                new RawAiCitation("Ghost Co v. Union of India", "Supreme Court", 2015, null, "point B")),
                        List.of("Partial issue document"), "Partial issue summary.", "test-model-partial", "partial_drafting_v2"));
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
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

        // Para-wise reply: AI paras used; the AI's Para 9 is beyond the matcher's 5 paragraphs: kept, with a warn flag
        String s05 = section(r, 5);
        assertTrue(s05.contains("Denied as regards the partial issue."));
        assertTrue(s05.contains("Denied as regards the new issue."));
        assertTrue(s05.contains("<strong>Para 9:</strong> Para that does not exist."));
        assertTrue(s05.contains("<strong>Para 6:</strong> [[PARTNER: reply to Para 6"));
        assertFalse(s05.contains("Para 10:"));
        assertEquals("warn", check(r, "FLAG_AI_PARA_BEYOND_NOTICE").severity());
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

        verify(partialAgent).generatePartialDrafting(argThat(i -> i.issueNo() == 2), any(), any(), eq("scn_73"), any(), eq(false), any());
        verify(newIssueAgent).generateNewIssueDraft(argThat(i -> i.issueNo() == 3), any(), eq("notice text"), eq("scn_73"), any(), eq(false), any());
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
    void writerFailureLeavesIssueToPartnerWithBlockFlag() {
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(null);
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));
        assertTrue(section(r, 4).contains(V7DraftingPipelineService.AI_FAILED_PLACEHOLDER));
        assertTrue(section(r, 6).contains(V7DraftingPipelineService.AI_FAILED_PLACEHOLDER));
        assertFalse(allHtml(r).contains("PENDING_AI"));
        DraftCheckResult flag = check(r, "FLAG_AI_DRAFT_FAILED");
        assertFalse(flag.passed());
        assertEquals("block", flag.severity());
        assertTrue(section(r, 13).contains("new-issue writer returned no usable output; left to the partner"));
        assertEquals(1, r.aiIssueSections().size(), "no ai_issue_sections row for the failed issue");
        verify(newIssueAgent, times(1)).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void costCapReachedLeavesRemainingIssuesToPartnerWithoutCalls() {
        AppProperties props = new AppProperties();
        props.getLlm().setModelMatching("priced-model");
        props.getLlm().setMaxCostInrPerNotice(1.0);
        AppProperties.Pricing.ModelPrice price = new AppProperties.Pricing.ModelPrice();
        price.setInputPerMillion(1_000_000.0);   // Stage 1 alone costs far more than INR 1
        props.getPricing().getModels().put("priced-model", price);
        V7DraftingPipelineService capped = new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly,
                new DraftCheckService(null), partialAgent, newIssueAgent, highStakesAgent, citationAgent,
                props, new ApiUsageLogService(null, props));
        // the matching agent records its own spend in the notice budget
        MatchingResult mr = matchingAgent.matchNoticeWithOcrText("", "", null, null, null);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenAnswer(inv -> {
            ((com.noticedesk.api.service.llm.NoticeCallBudget) inv.getArgument(4)).addCost("priced-model", 10, 0, 0, 0);
            return mr;
        });

        DraftingPipelineResult r = capped.runPipeline(input("Section 73"));

        verify(partialAgent, never()).generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean(), any());
        verify(newIssueAgent, never()).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
        assertEquals("block", check(r, "FLAG_AI_BUDGET_EXHAUSTED").severity());
        assertTrue(section(r, 4).contains(V7DraftingPipelineService.AI_SKIPPED_COST_CAP_PLACEHOLDER));
        assertTrue(section(r, 13).contains("per-notice cost cap reached"));
    }

    @Test
    void writerCallRefusedByWorstCaseCheck_leavesCostCapPlaceholderAndBlockFlag() {
        AppProperties props = new AppProperties();
        props.getLlm().setMaxCostInrPerNotice(5.0);
        AppProperties.Pricing.ModelPrice price = new AppProperties.Pricing.ModelPrice();
        price.setOutputPerMillion(20.0);
        props.getPricing().getModels().put("priced-model", price);
        V7DraftingPipelineService capped = new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly,
                new DraftCheckService(null), partialAgent, newIssueAgent, highStakesAgent, citationAgent,
                props, new ApiUsageLogService(null, props));
        // nothing spent yet, so the pipeline's own check passes; the writer's pre-call check refuses the call
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            com.noticedesk.api.service.llm.NoticeCallBudget b = inv.getArgument(6);
            assertNotNull(b.refusalReason("new_issue", "priced-model", 0, 16000));   // INR 28.16 worst case > INR 5
            return null;
        });

        DraftingPipelineResult r = capped.runPipeline(input("Section 73"));

        assertEquals("block", check(r, "FLAG_AI_BUDGET_EXHAUSTED").severity());
        assertTrue(allHtml(r).contains(V7DraftingPipelineService.AI_SKIPPED_COST_CAP_PLACEHOLDER));
        assertTrue(section(r, 13).contains("AI writer call not made (per-notice cost cap: worst case"), section(r, 13));
        assertFalse(section(r, 13).contains("new-issue writer returned no usable output"));
    }

    @Test
    void matchingRefusedByCostCap_failsTheNoticeWithACostCapReason() {
        MatchingResult refused = new MatchingResult(new MatchedNoticeInfo(null, null, null, null, null, null, null, null),
                List.of(new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of(), List.of(), List.of(IssueMatchingAgent.FLAG_COST_CAP))),
                Map.of(), List.of(IssueMatchingAgent.FLAG_COST_CAP, "per-notice cost cap: worst case of this call INR 7.04"),
                List.of(IssueMatchingAgent.FLAG_COST_CAP), "h", 0, 0, 0, 0);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(refused);

        MatchingFailedException e = assertThrows(MatchingFailedException.class, () -> pipeline.runPipeline(input("Section 73")));

        assertTrue(e.costCap());
        assertTrue(e.getMessage().contains("matching_cost_cap"), e.getMessage());
        verify(newIssueAgent, never()).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void trailingAiPrayerIsStrippedAndCitationsDeduplicated() {
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(3, "<p>New issue ground.</p>", "",
                        "<p>New issue legal submission.</p><p>In view of the above, it is prayed that the proceedings be dropped.</p>"
                                + "<p>It is further requested that a personal hearing be granted.</p>",
                        List.of(new RawAiCitation("Alpha Traders vs. State of Gujarat", "Gujarat High Court", 2019, null, "dup")),
                        null, List.of(), null, "test-model-new", "new_issue_v2"));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        String s06 = section(r, 6);
        assertTrue(s06.contains("New issue legal submission."));
        assertFalse(s06.contains("it is prayed"));
        assertFalse(s06.contains("personal hearing be granted"));
        assertEquals("info", check(r, "FLAG_AI_PRAYER_STRIPPED").severity());
        // "Alpha Traders v. State of Gujarat" (partial writer) and "... vs. ..." (new-issue writer): one citation
        assertEquals(1, r.citations().stream().filter(c -> c.caseName().startsWith("Alpha Traders")).count());
    }

    @Test
    void highStakesModeRoutesWritersAndWritesSections01And03() {
        when(highStakesAgent.isHighStakesActive(eq("Section 74"), anyString(), any())).thenReturn(true);
        when(highStakesAgent.generateHighStakesDraft(any(), any(), anyString(), any(), any()))
                .thenReturn(new HighStakesResult(true, "<p>High-stakes summary narrative.</p>", "<p>High-stakes background narrative.</p>",
                        List.of(), "test-model-hs", "high_stakes_v1"));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 74"));

        String s01 = section(r, 1);
        assertTrue(s01.contains("High-stakes summary narrative."));
        assertTrue(s01.contains("Reply to Notice Ref REF/1"), "code-built facts stay verbatim in high-stakes mode");
        assertTrue(section(r, 3).contains("High-stakes background narrative."));
        assertTrue(section(r, 3).contains("TESTGSTIN"));
        assertTrue(section(r, 4).contains("Template ground for Test Client Pvt Ltd on Rs 1,00,000."), "template blocks kept verbatim");
        verify(partialAgent).generatePartialDrafting(any(), any(), any(), anyString(), any(), eq(true), any());
        verify(newIssueAgent).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), eq(true), any());
        assertTrue(section(r, 13).contains("High-stakes mode active"));
        // the partner note names the high-stakes writer for the AI issues it wrote
        assertTrue(section(r, 13).contains("Issue #3: Match Status=none, Cards=[], Template=NONE (v0), Written by=AI high_stakes"),
                section(r, 13));
        assertFalse(section(r, 13).contains("Written by=AI new_issue"));
    }

    @Test
    void section74Reply_mentioningSection73InAiText_isNotAProceedingMismatch() {
        // s.74 notice: the AI legitimately discusses the Section 75(2) fallback to Section 73
        when(highStakesAgent.isHighStakesActive(eq("Section 74"), anyString(), any())).thenReturn(true);
        when(highStakesAgent.generateHighStakesDraft(any(), any(), anyString(), any(), any()))
                .thenReturn(new HighStakesResult(true,
                        "<p>Even if fraud is not proved, Section 75(2) only permits a redetermination under Section 73, "
                                + "and the penalty under Section 73(9) is far lower.</p>",
                        "<p>Background.</p>", List.of(), "test-model-hs", "high_stakes_v1"));
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(3, "<p>Ground.</p>", "", "<p>Submission.</p>", List.of(), null,
                        List.of("Copy of the order under Section 73 for the earlier year"),
                        "Under Section 75(2) the demand can at most be redetermined under Section 73.", "m", "new_issue_v2"));
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_74", 1, null, null, "active",
                Map.of("12", "<p>Prayer: drop the proceedings under {{notice.section}}.</p>", "15", "<p>Checklist for {{notice.section}}.</p>"),
                null, null, null));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 74"));

        assertTrue(section(r, 1).contains("Section 73"), "AI text kept as written");
        DraftCheckResult c = r.checkResults().stream().filter(x -> x.checkName().equals("CHECK_PROCEEDING_CONSISTENT")).findFirst().orElseThrow();
        assertTrue(c.passed(), String.valueOf(c.details()));
    }

    @Test
    void section74Reply_withASection73Prayer_isStillAMismatch() {
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_74", 1, null, null, "active",
                Map.of("12", "<p>Prayer: drop the proceedings under Section 73.</p>", "15", "<p>Checklist.</p>"), null, null, null));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 74"));

        DraftCheckResult c = r.checkResults().stream().filter(x -> x.checkName().equals("CHECK_PROCEEDING_CONSISTENT")).findFirst().orElseThrow();
        assertFalse(c.passed());
        assertTrue(c.details().toString().contains("section 12 refers to Section 73"), String.valueOf(c.details()));
    }

    @Test
    void matchingFailure_stopsTheNotice_noScaffoldNoWriterCall() {
        MatchingResult failed = new MatchingResult(new MatchedNoticeInfo(null, null, null, null, null, null, null, null),
                List.of(new MatchedIssue(1, "none", List.of(), "Matching failed: invalid JSON", List.of(), Map.of(), List.of(),
                        List.of("matching_failed"))),
                Map.of(), List.of("matching_failed", "invalid JSON at line 1 column 2"), List.of("matching_failed"), "h", 10, 5, 0, 0);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(failed);

        MatchingFailedException e = assertThrows(MatchingFailedException.class, () -> pipeline.runPipeline(input("Section 73")));

        assertTrue(e.getMessage().contains("matching_failed") && e.getMessage().contains("invalid JSON"), e.getMessage());
        verifyNoInteractions(partialAgent, newIssueAgent);
        verify(highStakesAgent, never()).generateHighStakesDraft(any(), any(), any(), any(), any());
    }

    @Test
    void fatalWriterError_propagates_noFurtherWriterCalls() {
        when(partialAgent.generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new LlmFatalException("Anthropic billing error (HTTP 400): credit balance is too low"));

        assertThrows(LlmFatalException.class, () -> pipeline.runPipeline(input("Section 73")));
        verifyNoInteractions(newIssueAgent);
    }

    @Test
    void prayerAndNoticeUnderstandingUseTheNoticesOwnSection() {
        assembly.registerInMemoryStageTemplate(new StageTemplate("anti_profiteering", 1, null, null, "active",
                Map.of("12", "<p>It is prayed to drop the proceedings under {{notice.section}} read with Rule 133.</p>",
                        "15", "<p>Checklist.</p>"), null, null, null));
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 171"));
        assertTrue(section(r, 2).contains("under Section 171 of the CGST Act, 2017"));
        assertTrue(section(r, 12).contains("drop the proceedings under Section 171 read with Rule 133"));
        assertFalse(section(r, 2).contains("Section 73"));
        assertFalse(section(r, 12).contains("Section 73"));
        // triage said scn_73; the Section 171 notice gets the anti_profiteering stage template
        assertTrue(check(r, "CHECK_PROCEEDING_CONSISTENT").passed());
        verify(newIssueAgent).generateNewIssueDraft(any(), any(), anyString(), eq("anti_profiteering"), any(), eq(false), any());
    }

    private static MatchingResult issues(MatchedIssue... issues) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/1", "DIN-1", "01-04-2024", "within 30 days", "2022-23", "2022-23", 160000.0, "Officer");
        return new MatchingResult(info, List.of(issues), Map.of("record", List.of(1), "issue", List.of(2, 3, 4), "demand", List.of(5)),
                List.of(), List.of(), "h", 1, 1, 0, 0);
    }

    @Test
    void unmatchedIssueWithoutASubstantiveGround_skipsTheNewIssueWriter() {
        // live finding: a corrigendum changing the figures of an earlier SCN (status none, no card, no amount) was sent
        // to the new-issue writer; an administrative label is skipped even when an amount is present
        Map<String, Object> noAmount = new HashMap<>();
        noAmount.put("period", "2022-23");
        noAmount.put("amount", null);
        noAmount.put("legal_section", null);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(issues(
                new MatchedIssue(1, "full", List.of("CARD-A"), "w", List.of(), Map.of("period", "2022-23", "amount", 100000.0), List.of("2"), List.of()),
                new MatchedIssue(2, "none", List.of(), "Corrigendum dated 02-04-2024 changing the figures of the earlier SCN", List.of(),
                        noAmount, List.of("3"), List.of()),
                new MatchedIssue(3, "none", List.of(), "Intimation of hearing date; no ground raised", List.of(),
                        Map.of("period", "2022-23", "amount", 10000.0), List.of("4"), List.of()),
                new MatchedIssue(4, "none", List.of(), "Supply treated as taxable at 18%", List.of(),
                        Map.of("period", "2022-23", "amount", 10000.0, "legal_section", "Section 9"), List.of("4"), List.of())));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        verify(newIssueAgent, never()).generateNewIssueDraft(argThat(i -> i.issueNo() == 2), any(), anyString(), anyString(), any(), anyBoolean(), any());
        verify(newIssueAgent, never()).generateNewIssueDraft(argThat(i -> i.issueNo() == 3), any(), anyString(), anyString(), any(), anyBoolean(), any());
        verify(newIssueAgent, times(1)).generateNewIssueDraft(argThat(i -> i.issueNo() == 4), any(), anyString(), anyString(), any(), anyBoolean(), any());
        verify(partialAgent, never()).generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean(), any());

        String s04 = section(r, 4);
        assertTrue(s04.contains("<h3>Issue #2 Grounds of Defence</h3>"), s04);
        assertTrue(s04.contains("4.2 " + V7DraftingPipelineService.NO_SUBSTANTIVE_GROUND_PLACEHOLDER), s04);
        assertTrue(s04.contains("4.3 " + V7DraftingPipelineService.NO_SUBSTANTIVE_GROUND_PLACEHOLDER), s04);
        assertTrue(s04.contains("New issue ground."), "the substantive unmatched issue is still written");
        assertFalse(allHtml(r).contains("PENDING_AI"));
        DraftCheckResult skipped = check(r, "FLAG_NEW_ISSUE_WRITER_SKIPPED");
        assertEquals("info", skipped.severity());
        assertEquals(V7DraftingPipelineService.FLAG_NEW_ISSUE_WRITER_SKIPPED, skipped.details().get("flag"));
        assertTrue(section(r, 13).contains("Issue #2: new-issue writer not called (no catalogue card and no substantive demand"), section(r, 13));
        assertTrue(section(r, 13).contains("Written by=AI none (new-issue writer skipped: no substantive ground)"), section(r, 13));
        assertEquals(1, r.aiIssueSections().size(), "no ai_issue_sections row for a skipped issue");
        assertEquals(4, r.aiIssueSections().get(0).issueNo());
    }

    @Test
    void lacksSubstantiveGround_rules() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("period", "2022-23");
        assertTrue(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of(), "Some ground", List.of(), empty, List.of(), List.of())));
        assertTrue(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of(), "Reminder to file the reply", List.of(), Map.of("amount", 5.0), List.of(), List.of())));
        assertTrue(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of(), "Acknowledgement of the reply filed", List.of(), Map.of("legal_section", "Section 73"), List.of(), List.of())));
        assertFalse(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of(), "ITC on blocked credit", List.of(), Map.of("legal_section", "Section 17(5)"), List.of(), List.of())));
        assertFalse(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of(), "Excess ITC", List.of(), Map.of("amount", 5.0), List.of(), List.of())));
        // only an unmatched issue without a card is tested
        assertFalse(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "none", List.of("CARD-A"), "Corrigendum", List.of(), empty, List.of(), List.of())));
        assertFalse(V7DraftingPipelineService.lacksSubstantiveGround(
                new MatchedIssue(1, "partial", List.of(), "Corrigendum", List.of(), empty, List.of(), List.of())));
    }

    @Test
    void fullMatchFilledFromATemplate_neverReachesAWriter_evenForFy2017_18OrAMismatchedWindow() {
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-C", 1, "CARD-C",
                LocalDate.of(2021, 1, 1), LocalDate.of(2022, 2, 28), "active",
                List.of(new TemplateBlock("BLK-C-04", 4, "<p>Template ground C.</p>", List.of(), null)),
                List.of(), List.of(), "Template C summary.", List.of(), null, null, null));
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(issues(
                // FY 2017-18 starts before every card's 2017-07-01 effective_from: the version is still chosen
                new MatchedIssue(1, "full", List.of("CARD-A"), "w", List.of(), Map.of("period", "FY 2017-18", "amount", 100000.0), List.of("2"), List.of()),
                // no version of CARD-C overlaps FY 2019-20: the nearest version is used with a warn flag
                new MatchedIssue(2, "full", List.of("CARD-C"), "w", List.of(), Map.of("period", "FY 2019-20", "amount", 50000.0), List.of("3"), List.of())));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        verifyNoInteractions(partialAgent, newIssueAgent);
        String s04 = section(r, 4);
        assertTrue(s04.contains("Template ground for Test Client Pvt Ltd on Rs 1,00,000."), s04);
        assertTrue(s04.contains("Template ground C."), s04);
        assertFalse(allHtml(r).contains("PENDING_AI"));
        String s13 = section(r, 13);
        assertTrue(s13.contains("Issue #1: Match Status=full, Cards=[CARD-A], Template=TPL-A (v1), Written by=template (code)"), s13);
        assertTrue(s13.contains("Issue #2: Match Status=full, Cards=[CARD-C], Template=TPL-C (v1), Written by=template (code)"), s13);
        assertFalse(s13.contains("No template available"), s13);
        assertTrue(s13.contains("Note=No template version of card CARD-C covers the period 2019-04-01 to 2020-03-31; nearest version v1 (2021-01-01 to 2022-02-28) used"), s13);
        DraftCheckResult mismatch = check(r, "FLAG_TEMPLATE_WINDOW_MISMATCH");
        assertEquals("warn", mismatch.severity());
        assertEquals("CARD-C", mismatch.details().get("card_id"));
        assertEquals("2019-04-01", mismatch.details().get("period_start"));
        assertEquals("2020-03-31", mismatch.details().get("period_end"));
        assertEquals("2021-01-01", mismatch.details().get("template_effective_from"));
        assertEquals("2022-02-28", mismatch.details().get("template_effective_to"));
        assertTrue(r.checkResults().stream().noneMatch(c -> c.checkName().equals("FLAG_PERIOD_CROSSES_TEMPLATE_VERSION")));
        assertTrue(check(r, "CHECK_EVERY_ISSUE_ANSWERED").passed());
    }

    @Test
    void defectIssue_isAnsweredInSection07AndPassesTheIssueCheck() {
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(issues(
                new MatchedIssue(1, "full", "demand", List.of("CARD-A"), List.of(), "w", List.of(),
                        Map.of("period", "2022-23", "amount", 100000.0), List.of("2"), List.of(), List.of(), null),
                new MatchedIssue(2, "full", "defect", List.of("CARD-B"), List.of(), "no DIN", List.of(),
                        Map.of("period", "2022-23", "legal_section", "Circular 128/47/2019-GST"), List.of("header"), List.of(), List.of(), null),
                new MatchedIssue(3, "none", "defect", List.of(), List.of(), "time-barred under Section 73(10)", List.of(),
                        Map.of("period", "2022-23", "legal_section", "Section 73(10)"), List.of("header"), List.of(), List.of(), null)));

        DraftingPipelineResult r = pipeline.runPipeline(input("Section 73"));

        verify(newIssueAgent, times(1)).generateNewIssueDraft(argThat(i -> i.issueNo() == 3), any(), anyString(), anyString(), any(), anyBoolean(), any());
        String s04 = section(r, 4);
        assertTrue(s04.contains("Issue #1 Grounds of Defence"), s04);
        assertFalse(s04.contains("Issue #2"), s04);
        assertFalse(s04.contains("Issue #3"), s04);
        String s07 = section(r, 7);
        assertTrue(s07.contains("<h3>Issue #2 Procedural Objection</h3>"), s07);
        assertTrue(s07.contains("7.1 Template ground B that does not fit this notice."), s07);
        assertTrue(s07.contains("<h3>Issue #3 Procedural Objection</h3>"), s07);
        assertTrue(s07.contains("7.2 New issue ground."), s07);
        assertTrue(s07.contains("7.3 New issue legal submission."), s07);
        assertFalse(s07.contains("[[PARTNER: add procedural objections"), s07);
        assertFalse(section(r, 6).contains("Issue #3"), section(r, 6));
        assertTrue(check(r, "CHECK_EVERY_ISSUE_ANSWERED").passed(), String.valueOf(check(r, "CHECK_EVERY_ISSUE_ANSWERED").details()));
    }

    @Test
    void missingStageTemplateIsBlockedNotReplacedByScn73() {
        DraftingPipelineResult r = pipeline.runPipeline(input("Section 74"));   // no scn_74 template registered
        assertTrue(section(r, 12).contains("[[MISSING: stage template for scn_74]]"));
        assertFalse(section(r, 12).contains("PRAYER"), "the scn_73 prayer must not be used");
        assertEquals("block", check(r, "FLAG_STAGE_TEMPLATE_MISSING").severity());
    }
}
