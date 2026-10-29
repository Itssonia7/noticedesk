package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.llm.NoticeCallBudget;
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
 * The new-issue writer on its own provider ({@code llm.provider-new-issue}): provider and model resolution, a
 * zero-priced (free tier) model passing the per-notice cap, and the writer's tolerant parse of a fenced answer.
 * Mocked provider, no live calls.
 */
class GeminiNewIssueWriterTest {

    /** The GEMINI_MODEL default of application.yml (the free-tier price entry is keyed by it). */
    private static final String GEMINI = TestAppProperties.fromApplicationYml().getLlm().getGemini().getModel();
    private static final String FENCED_JSON = """
            Here is the reply:
            ```json
            {"section04_html": "<p>Grounds</p>", "section05_html": "", "section06_html": "<p>Submissions</p>", "citations": []}
            ```
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
        props = TestAppProperties.fromApplicationYml();   // real pricing table (the free-tier entry included)
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setProviderNewIssue("gemini");
        props.getLlm().setModelNewIssue("");
        props.getLlm().getGemini().setModel(GEMINI);
        pricing = new ApiUsageLogService(null, props);
        llm = Mockito.mock(LlmProvider.class);
        Mockito.when(llm.getName()).thenReturn("gemini");
        factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent("new_issue")).thenReturn(llm);
    }

    @Test
    void providerAndModelPerAgent() {
        AppProperties.Llm l = props.getLlm();
        assertEquals("gemini", l.providerForAgent("new_issue"));
        assertEquals("anthropic", l.providerForAgent("high_stakes"), "blank: the primary provider");
        assertEquals("anthropic", l.providerForAgent("matching"));
        assertEquals(GEMINI, props.writerModel("new_issue", ""), "no LLM_MODEL_NEW_ISSUE: GEMINI_MODEL");
        l.setModelNewIssue("gemini-other");
        assertEquals("gemini-other", props.writerModel("new_issue", l.getModelNewIssue()), "LLM_MODEL_NEW_ISSUE wins");
        assertEquals("gemini-other", props.modelForAgent("new_issue", "gemini"));
        assertEquals("claude-x", props.writerModel("high_stakes", "claude-x"), "an anthropic writer keeps its own model");
        l.setProviderHighStakes(" Gemini ");
        assertEquals("gemini", l.providerForAgent("high_stakes"), "trimmed, lower-cased");
    }

    @Test
    void applicationYmlBindsTheFreeTierPriceAndTheShortlistDefaults() {
        AppProperties yml = TestAppProperties.fromApplicationYml();
        AppProperties.Pricing.ModelPrice price = yml.getPricing().getModels().get(GEMINI);
        assertNotNull(price, "a dotted model id needs the [..] key form in application.yml");
        assertEquals(0.0, price.getInputPerMillion());
        assertEquals(0.0, price.getOutputPerMillion());
        AppProperties.Matching.Shortlist s = yml.getMatching().getShortlist();
        if (System.getenv("MATCHING_SHORTLIST_MIN_CARDS_WITH_AI") == null) assertEquals(12, s.getMinCardsWithAi());
        if (System.getenv("MATCHING_SHORTLIST_ABSENCE_TITLE_PATTERNS") == null) {
            assertEquals(List.of("no section", "vague"), s.getAbsenceTitlePatterns());
        }
    }

    @Test
    void zeroPricedModelIsAllowed_aMissingModelIsStillRefused() {
        assertTrue(pricing.hasPrice(GEMINI));
        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal("5"), pricing);
        NoticeCallBudget.Prompt prompt = NoticeCallBudget.Prompt.of(GEMINI, "system prompt and notice", "issue");
        assertEquals(0, BigDecimal.ZERO.compareTo(NoticeCallBudget.estimate(pricing, prompt, 24000).totalInr()), "worst case 0");
        assertNull(budget.refusalReason("new_issue", prompt, 24000));

        String refusal = budget.refusalReason("new_issue", NoticeCallBudget.Prompt.of("gemini-not-in-table", "s", "u"), 100);
        assertNotNull(refusal);
        assertTrue(refusal.contains("no price configured"), refusal);
    }

    @Test
    void writerOnGemini_fencedJsonParses_zeroCostRecorded() {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                        Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(FENCED_JSON, GEMINI, "gemini", 4000, 900, "end_turn", 0, 0));
        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal("5"), pricing);
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, pricing, factory);

        NewIssueDraftingAgent.NewIssueDraftingResult r =
                agent.generateNewIssueDraft(issue, notice, "ocr", "scn_73", UUID.randomUUID(), false, budget);

        assertNotNull(r);
        assertEquals("<p>Grounds</p>", r.section04Html());
        assertEquals(GEMINI, r.model());
        // the call names the gemini model and the new-issue max_tokens
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(GEMINI),
                Mockito.eq(props.maxTokensForAgent("new_issue", false)), Mockito.any(), Mockito.anyBoolean());
        assertEquals(0, budget.budgetRefusals());
        assertEquals(0, BigDecimal.ZERO.compareTo(budget.spentInr()));
    }

    @Test
    void factoryBuildsTheGeminiProviderForTheNewIssueWriterOnly() {
        props.getLlm().getAnthropic().setApiKey("test-anthropic-key");
        props.getLlm().getGemini().setApiKey("test-gemini-key");
        props.getLlm().setModelMatching("claude-sonnet-5-5");
        LlmFactory real = new LlmFactory(props);

        LlmProvider writer = real.getLlmForAgent("new_issue");
        assertEquals("gemini", writer.getName());
        assertEquals(GEMINI, writer.getModel());
        assertEquals("anthropic", real.getLlmForAgent("matching").getName());
        assertEquals("anthropic", real.getLlmForAgent("high_stakes").getName());
    }
}
