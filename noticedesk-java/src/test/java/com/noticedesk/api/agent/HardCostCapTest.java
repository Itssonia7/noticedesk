package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hard cost caps: every billed call (first attempt and retry) is refused before it is made when
 * spent + its worst-case cost would exceed the cap. Pricing and max_tokens from application.yml; mocked
 * provider, no live calls.
 */
class HardCostCapTest {

    private static final String OPUS = "claude-opus-5-5";
    private static final String SONNET = "claude-sonnet-5-5";
    private static final String HAIKU = "claude-haiku-4-5-20251001";
    private static final String NEW_ISSUE_JSON = """
            {"section04_html": "<p>Grounds</p>", "section05_html": "", "section06_html": "<p>Submissions</p>", "citations": []}
            """;

    private final ObjectMapper om = new ObjectMapper();
    private final MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "why", List.of(), Map.of(), List.of(), List.of());
    private final MatchedNoticeInfo notice = new MatchedNoticeInfo(null, null, null, null, null, null, null, null);

    private AppProperties props;
    private ApiUsageLogService pricing;
    private LlmProvider llm;
    private LlmFactory factory;

    @BeforeEach
    void setUp() {
        props = TestAppProperties.fromApplicationYml();   // real pricing, INR rate and max_tokens
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching(SONNET);
        props.getLlm().setModelNewIssue(OPUS);
        pricing = new ApiUsageLogService(null, props);
        llm = Mockito.mock(LlmProvider.class);
        factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent(Mockito.anyString())).thenReturn(llm);
    }

    private NoticeCallBudget budget(String cap, String spent) {
        NoticeCallBudget b = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal(cap), pricing);
        b.addSpentInr(new BigDecimal(spent));
        return b;
    }

    private void stubWriter(LlmResponse first, LlmResponse... rest) {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(first, rest);
    }

    private void verifyNoWriterCall() {
        Mockito.verify(llm, Mockito.never()).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void worstCase_isMaxTokensAtOutputPrice_plusInputAtTheHigherOfInputAndCacheWrite() {
        // claude-opus-5-5: output $20/M, cache write $5/M (> input $4/M), INR 88 per USD
        assertEquals(0, new BigDecimal("28.16").compareTo(NoticeCallBudget.worstCaseInr(pricing, OPUS, 0, 16000)));
        // 30,000 chars -> 15,000 tokens (2 chars per token) at $5/M = $0.075 = INR 6.60, + INR 28.16
        assertEquals(0, new BigDecimal("34.76").compareTo(NoticeCallBudget.worstCaseInr(pricing, OPUS, 30_000, 16000)));
        assertEquals(2, NoticeCallBudget.CHARS_PER_TOKEN);
        assertEquals(15_000, NoticeCallBudget.estimateInputTokens(30_000));
        assertEquals(1, NoticeCallBudget.estimateInputTokens(1));
        assertEquals(1, NoticeCallBudget.estimateInputTokens(2));
        assertEquals(2, NoticeCallBudget.estimateInputTokens(3));
        assertEquals(0, NoticeCallBudget.estimateInputTokens(0));
        // live matching call (cached catalogue): ~15,540 prompt chars used ~7,770 input tokens; the
        // estimate must not be below the actual count
        assertTrue(NoticeCallBudget.estimateInputTokens(15_540) >= 7_770);
        assertNull(NoticeCallBudget.worstCaseInr(pricing, "unpriced-model", 0, 100));
    }

    /** (a) The smoke-run case: matching spent INR 2.61 of a INR 5 cap; an Opus new_issue call must not be made. */
    @Test
    void opusNewIssueCall_afterMatchingSpend_isRefusedBeforeTheCall() {
        assertEquals(16000, props.getLlm().maxTokensForAgent("new_issue", false));
        NoticeCallBudget budget = budget("5", "2.61");
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, null, factory);

        assertNull(agent.generateNewIssueDraft(issue, notice, "ocr", "scn_73", UUID.randomUUID(), false, budget));

        verifyNoWriterCall();
        assertEquals(1, budget.budgetRefusals());
        assertTrue(budget.lastRefusal().contains("worst case"), budget.lastRefusal());
        assertEquals(0, new BigDecimal("2.61").compareTo(budget.spentInr()));
    }

    /** (b) A cheap call whose worst case fits under the cap is made. */
    @Test
    void cheapCall_thatFits_isAllowed() {
        props.getLlm().setModelNewIssue(HAIKU);
        props.getLlm().setMaxTokensNewIssue(2000);   // worst case ~INR 0.70 + input
        stubWriter(new LlmResponse(NEW_ISSUE_JSON, HAIKU, "anthropic", 500, 300, "end_turn", 0, 0));
        NoticeCallBudget budget = budget("5", "2.61");
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, null, factory);

        assertNotNull(agent.generateNewIssueDraft(issue, notice, "ocr", "scn_73", UUID.randomUUID(), false, budget));

        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(HAIKU),
                Mockito.eq(2000), Mockito.any(), Mockito.anyBoolean());
        assertEquals(0, budget.budgetRefusals());
        assertTrue(budget.spentInr().compareTo(new BigDecimal("2.61")) > 0, "the call's cost is recorded");
    }

    /** (c) Live runner: the notice budget is what is left of the run cap; a matching call that won't fit is never made. */
    @Test
    void runCap_remainingRunBudget_refusesMatchingBeforeTheCall() {
        LiveRunGuard guard = new LiveRunGuard(Map.of("LIVE_MAX_RUN_COST_INR", "20", "LIVE_MAX_NOTICE_COST_INR", "30")::get);
        BigDecimal noticeCap = guard.noticeCapFor(new BigDecimal("13.19"));
        assertEquals(0, new BigDecimal("6.81").compareTo(noticeCap));
        assertEquals(0, BigDecimal.ZERO.compareTo(guard.noticeCapFor(new BigDecimal("20"))));
        assertEquals(0, new BigDecimal("20").compareTo(guard.noticeCapFor(BigDecimal.ZERO)));

        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, noticeCap, pricing);
        // Sonnet matching at max_tokens 12000: output alone is INR 10.56 > 6.81
        MatchingResult r = matcher().matchNoticeWithOcrText("synthetic notice", "scn_73", UUID.randomUUID(), UUID.randomUUID(), budget);

        assertTrue(r.flags().contains(IssueMatchingAgent.FLAG_COST_CAP), r.flags().toString());
        assertTrue(com.noticedesk.api.service.V7DraftingPipelineService.matchingFailure(r).contains("cost cap"));
        Mockito.verify(llm, Mockito.never()).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
    }

    /** (d) A writer retry (raised max_tokens) is checked again: first call allowed, retry refused. */
    @Test
    void writerRetry_withRaisedCap_isCheckedAndRefused() {
        // first attempt: worst case ~INR 28.2 fits a INR 35 cap; it hits max_tokens and costs ~INR 28.5;
        // the retry at 24000 max_tokens (worst case ~INR 42) does not fit and is not made
        stubWriter(new LlmResponse("", OPUS, "anthropic", 1000, 16000, "max_tokens", 0, 0, LlmResponse.FAILURE_MAX_TOKENS),
                new LlmResponse(NEW_ISSUE_JSON, OPUS, "anthropic", 1000, 4000, "end_turn", 0, 0));
        NoticeCallBudget budget = budget("35", "0");
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, null, factory);

        assertNull(agent.generateNewIssueDraft(issue, notice, "ocr", "scn_73", UUID.randomUUID(), false, budget));

        Mockito.verify(llm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(OPUS),
                Mockito.eq(16000), Mockito.any(), Mockito.anyBoolean());
        assertEquals(1, budget.budgetRefusals());
        assertTrue(budget.lastRefusal().contains("worst case"));
    }

    /** (d) A matching retry is checked too. */
    @Test
    void matchingRetry_isCheckedAndRefused() {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("", SONNET, "anthropic", 1000, 12000, "max_tokens", 0, 0, LlmResponse.FAILURE_MAX_TOKENS));
        // first call (max_tokens 12000) worst case ~INR 10.6 fits INR 15; it costs ~INR 10.74; the retry at 20000
        // (output alone INR 17.60) does not fit the remaining ~INR 4.26 and is not made
        NoticeCallBudget budget = budget("15", "0");

        MatchingResult r = matcher().matchNoticeWithOcrText("synthetic notice", "scn_73", UUID.randomUUID(), UUID.randomUUID(), budget);

        assertTrue(r.flags().contains(IssueMatchingAgent.FLAG_COST_CAP), r.flags().toString());
        Mockito.verify(llm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(SONNET), Mockito.eq(12000),
                Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(llm, Mockito.never()).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(20000),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void unpricedModel_withACap_isRefused() {
        props.getLlm().setModelNewIssue("unpriced-model");
        NoticeCallBudget budget = budget("1000", "0");

        assertNull(new NewIssueDraftingAgent(props, om, null, factory)
                .generateNewIssueDraft(issue, notice, "ocr", "scn_73", UUID.randomUUID(), false, budget));

        verifyNoWriterCall();
        assertTrue(budget.lastRefusal().contains("no price configured"));
    }

    private IssueMatchingAgent matcher() {
        CatalogueService catalogue = Mockito.mock(CatalogueService.class);
        Mockito.when(catalogue.getActiveCards()).thenReturn(List.of());
        Mockito.when(catalogue.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue Text");
        return new IssueMatchingAgent(factory, catalogue, Mockito.mock(ApiUsageLogService.class), props, null, om);
    }
}
