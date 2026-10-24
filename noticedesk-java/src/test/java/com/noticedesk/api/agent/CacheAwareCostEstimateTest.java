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
import com.noticedesk.api.service.llm.NoticeCallBudget.Estimate;
import com.noticedesk.api.service.llm.NoticeCallBudget.Prompt;
import com.noticedesk.api.service.llm.PromptCacheRegistry;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cache-aware worst case: the cacheable prefix (system prompt + catalogue, or the writers' notice prefix)
 * is priced at the cache-write rate on the first call of a process and at the cache-read rate, with the
 * token count the API reported, once the same prefix was sent within the cache's warm window. Pricing
 * from application.yml; mocked provider, no live calls.
 */
class CacheAwareCostEstimateTest {

    private static final String SONNET = "claude-sonnet-5-5";
    private static final String OPUS = "claude-opus-5-5";
    private static final int CATALOGUE_TOKENS = 83_000;
    /** application.yml default since prompt v3.1 (the matcher sends it; the estimate below must follow it). */
    private static final int MATCHING_MAX_TOKENS = 12000;
    /** ~270 real cards: ~330k characters. */
    private static final String CATALOGUE = "C".repeat(330_000);
    private static final String MATCHING_JSON = """
            {"notice": {"notice_number": "X"}, "issues": [{"issue_no": 1, "status": "none", "card_ids": [], "why": "w", "facts": {}}], "para_map": {}}
            """;
    private static final String NEW_ISSUE_JSON = """
            {"section04_html": "<p>Grounds</p>", "section05_html": "", "section06_html": "<p>Submissions</p>", "citations": []}
            """;

    private final ObjectMapper om = new ObjectMapper();
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

    private static BigDecimal inr(String s) {
        return new BigDecimal(s);
    }

    private static void assertInr(String expected, BigDecimal actual) {
        assertEquals(0, inr(expected).compareTo(actual.setScale(2, RoundingMode.HALF_UP)),
                "expected INR " + expected + " but was " + actual.setScale(4, RoundingMode.HALF_UP));
    }

    /** Sonnet 5.5: output $10/M, input $2/M, cache write $2.50/M, cache read $0.20/M; INR 88 per USD. */
    @Test
    void prefixIsPricedAsCacheWriteOnTheFirstCall_andCacheReadWithMeasuredTokensOnTheNext() {
        Prompt prompt = Prompt.of(SONNET, "rules\n" + CATALOGUE, "NOTICE TEXT " + "n".repeat(20_000));

        // (a) first call of the process: nothing known about the cache -> character estimate at cache-write
        Estimate first = NoticeCallBudget.estimate(pricing, prompt, MATCHING_MAX_TOKENS);
        assertEquals("cache_write", first.prefixPricing());
        assertFalse(first.prefixMeasured());
        assertEquals(NoticeCallBudget.estimateInputTokens(prompt.prefixChars()), first.prefixTokens());   // 165,003 at 2 chars/token
        assertEquals(MATCHING_MAX_TOKENS, props.getLlm().maxTokensForAgent("matching", false), "yml default");
        assertInr("10.56", first.outputInr());                    // 12,000 x $10/M x 88
        assertInr("1.76", first.perCallInr());                    // 20,006 chars -> 10,003 tokens x $2/M x 88 = 1.7605
        assertInr("36.30", first.prefixInr());                    // 165,003 x $2.50/M x 88
        assertInr("48.62", first.totalInr());

        // the API answers with the catalogue's real size as cache_creation tokens
        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, null, pricing);
        budget.recordCall("matching", new LlmResponse(MATCHING_JSON, SONNET, "anthropic", 10_003, 3000, "end_turn", CATALOGUE_TOKENS, 0),
                SONNET, prompt);

        // (b) second call with the same prefix: measured tokens at the cache-read rate
        Estimate second = NoticeCallBudget.estimate(pricing, prompt, MATCHING_MAX_TOKENS);
        assertEquals("cache_read", second.prefixPricing());
        assertTrue(second.prefixMeasured());
        assertEquals(CATALOGUE_TOKENS, second.prefixTokens());
        assertInr("1.46", second.prefixInr());                    // 83,000 x $0.20/M x 88 = 1.4608
        assertInr("1.76", second.perCallInr());
        assertInr("10.56", second.outputInr());
        assertInr("13.78", second.totalInr());

        // a different model has its own cache; a different catalogue is a different prefix
        assertEquals("cache_write", NoticeCallBudget.estimate(pricing, Prompt.of(OPUS, "rules\n" + CATALOGUE, "x"), 100).prefixPricing());
        assertEquals("cache_write", NoticeCallBudget.estimate(pricing, Prompt.of(SONNET, "rules\n" + CATALOGUE + "!", "x"), 100).prefixPricing());
        // no prefix: everything is per-call input at the input price
        Estimate none = NoticeCallBudget.estimate(pricing, Prompt.uncached(SONNET, 2000), 100);
        assertEquals("none", none.prefixPricing());
        assertEquals(1000, none.perCallTokens());
        assertNull(NoticeCallBudget.estimate(pricing, Prompt.of("unpriced-model", CATALOGUE, "x"), 100));
    }

    /**
     * The reported figures (Sonnet matching on an 83,000-token catalogue, per-call part empty), at the v3.1
     * max_tokens of 12000 (was 8000: Rs 25.30 cold / Rs 8.50 warm).
     */
    @Test
    void reportedWorstCases_for83kTokenCatalogue() {
        Prompt prompt = Prompt.of(SONNET, CATALOGUE, "");
        // the catalogue's measured size is known (e.g. from the usage log of an earlier run) but the cache is cold
        pricing.promptCache().recordMeasuredTokens(SONNET, prompt.prefixHash(), CATALOGUE_TOKENS);

        Estimate first = NoticeCallBudget.estimate(pricing, prompt, MATCHING_MAX_TOKENS);
        assertEquals("cache_write", first.prefixPricing());
        assertTrue(first.prefixMeasured());
        assertInr("18.26", first.prefixInr());                    // 83,000 x $2.50/M x 88
        assertInr("28.82", first.totalInr());                     // + 10.56 output

        pricing.promptCache().recordUse(SONNET, prompt.prefixHash(), CATALOGUE_TOKENS);
        Estimate next = NoticeCallBudget.estimate(pricing, prompt, MATCHING_MAX_TOKENS);
        assertEquals("cache_read", next.prefixPricing());
        assertInr("1.46", next.prefixInr());
        assertInr("12.02", next.totalInr());
    }

    /** A cap of INR 15 refuses the first matching call on the 83k-token catalogue but allows the second. */
    @Test
    void capOf15_refusesFirstMatchingCall_allowsSecondWithWarmCatalogue() {
        IssueMatchingAgent matcher = matcher();
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(SONNET), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(MATCHING_JSON, SONNET, "anthropic", 600, 1500, "end_turn", CATALOGUE_TOKENS, 0))
                .thenReturn(new LlmResponse(MATCHING_JSON, SONNET, "anthropic", 600, 1500, "end_turn", 0, CATALOGUE_TOKENS));

        // first notice of the process: cold catalogue, character estimate at cache-write (~INR 36 + 10.56): refused, no call
        NoticeCallBudget first = new NoticeCallBudget(Integer.MAX_VALUE, inr("15"), pricing);
        MatchingResult r1 = matcher.matchNoticeWithOcrText("synthetic notice text", "scn_73", UUID.randomUUID(), UUID.randomUUID(), first);
        assertTrue(r1.flags().contains(IssueMatchingAgent.FLAG_COST_CAP), r1.flags().toString());
        assertTrue(first.lastRefusal().contains("worst case"), first.lastRefusal());
        Mockito.verify(llm, Mockito.never()).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());

        // a notice whose cap allows it writes the catalogue to the cache: the API reports 83,000 cache_creation tokens
        NoticeCallBudget big = new NoticeCallBudget(Integer.MAX_VALUE, inr("100"), pricing);
        MatchingResult r2 = matcher.matchNoticeWithOcrText("synthetic notice text", "scn_73", UUID.randomUUID(), UUID.randomUUID(), big);
        assertFalse(r2.flags().contains(IssueMatchingAgent.FLAG_COST_CAP), r2.flags().toString());
        // recorded spend: 600 in x $2 + 1,500 out x $10 + 83,000 cache write x $2.50 per M = $0.2237 x 88
        assertInr("19.69", big.spentInr());

        // the next notice under the INR 15 cap: catalogue priced at cache-read with the measured 83,000 tokens
        // (1.46) + output 10.56 (max_tokens 12000 from application.yml) + the notice text at the input price: allowed, call made
        NoticeCallBudget second = new NoticeCallBudget(Integer.MAX_VALUE, inr("15"), pricing);
        MatchingResult r3 = matcher.matchNoticeWithOcrText("synthetic notice text", "scn_73", UUID.randomUUID(), UUID.randomUUID(), second);
        assertFalse(r3.flags().contains(IssueMatchingAgent.FLAG_COST_CAP), r3.flags().toString());
        assertEquals(0, second.budgetRefusals());
        Mockito.verify(llm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(SONNET), Mockito.eq(MATCHING_MAX_TOKENS), Mockito.any(), Mockito.anyBoolean());
        // recorded spend of a cache-read call: 600 x $2 + 1,500 x $10 + 83,000 x $0.20 per M = $0.0328 x 88
        assertInr("2.89", second.spentInr());
    }

    /** Writers: the first issue of a notice writes the notice prefix; the next issues read it (same behaviour, cheaper estimate). */
    @Test
    void writerPrefix_isCacheReadForTheNextIssueOfTheSameNotice() {
        MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "why", List.of(), Map.of(), List.of(), List.of());
        MatchedNoticeInfo notice = new MatchedNoticeInfo("N-1", null, null, null, null, null, null, null);
        String ocr = "o".repeat(40_000);   // notice prefix ~20,000 tokens by the character estimate
        UUID noticeId = UUID.randomUUID();
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(OPUS), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(NEW_ISSUE_JSON, OPUS, "anthropic", 200, 4000, "end_turn", 30_000, 0))
                .thenReturn(new LlmResponse(NEW_ISSUE_JSON, OPUS, "anthropic", 200, 4000, "end_turn", 0, 30_000));
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, null, factory);

        // Opus 5.5 at 16,000 max_tokens: output INR 28.16; the cold prefix (20,000 tokens x $5/M x 88 = 8.80) does not fit INR 31
        NoticeCallBudget cold = new NoticeCallBudget(Integer.MAX_VALUE, inr("31"), pricing);
        assertNull(agent.generateNewIssueDraft(issue, notice, ocr, "scn_73", noticeId, false, cold));
        assertEquals(1, cold.budgetRefusals());

        // first issue written under a wider cap: the API reports 30,000 cache_creation tokens for the prefix
        NoticeCallBudget wide = new NoticeCallBudget(Integer.MAX_VALUE, inr("100"), pricing);
        assertNotNull(agent.generateNewIssueDraft(issue, notice, ocr, "scn_73", noticeId, false, wide));

        // second issue of the same notice: prefix at cache-read (30,000 x $0.20/M x 88 = 0.53) fits INR 31
        NoticeCallBudget warm = new NoticeCallBudget(Integer.MAX_VALUE, inr("31"), pricing);
        assertNotNull(agent.generateNewIssueDraft(new MatchedIssue(2, "none", List.of(), "why", List.of(), Map.of(), List.of(), List.of()),
                notice, ocr, "scn_73", noticeId, false, warm));
        assertEquals(0, warm.budgetRefusals());
        Mockito.verify(llm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(OPUS),
                Mockito.eq(16000), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void registry_warmWindowIsTtlMinusMargin_andMeasuredTokensSurviveExpiry() {
        Instant t0 = Instant.parse("2026-10-12T10:00:00Z");
        Clock[] clock = {Clock.fixed(t0, ZoneOffset.UTC)};
        PromptCacheRegistry registry = new PromptCacheRegistry(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        });
        String hash = PromptCacheRegistry.hash("prefix");
        assertFalse(registry.isWarm(SONNET, hash));
        registry.recordUse(SONNET, hash, 1234);
        assertTrue(registry.isWarm(SONNET, hash));
        assertEquals(1234, registry.measuredTokens(SONNET, hash));

        clock[0] = Clock.fixed(t0.plus(PromptCacheRegistry.CACHE_TTL.minus(PromptCacheRegistry.WARM_MARGIN)), ZoneOffset.UTC);
        assertTrue(registry.isWarm(SONNET, hash), "still warm at the edge of the window");
        clock[0] = Clock.fixed(t0.plus(PromptCacheRegistry.CACHE_TTL).plus(Duration.ofSeconds(1)), ZoneOffset.UTC);
        assertFalse(registry.isWarm(SONNET, hash), "cold after the TTL");
        assertEquals(1234, registry.measuredTokens(SONNET, hash), "the measured size is still known");

        // a use with no reported cache tokens keeps the earlier measured count; an empty hash is ignored
        registry.recordUse(SONNET, hash, 0);
        assertEquals(1234, registry.measuredTokens(SONNET, hash));
        registry.recordUse(SONNET, "", 50);
        assertNull(registry.measuredTokens(SONNET, ""));
        assertEquals("", PromptCacheRegistry.hash(null));
        assertEquals(64, PromptCacheRegistry.hash("x").length());
    }

    /**
     * Shortlist on (default): the system prompt is the rules alone, the shortlisted cards go in the per-call user
     * message, the budget's cacheable prefix is the rules, and the catalogue hash is that of the full catalogue. With
     * the shortlist off the full catalogue stays in the system prompt.
     */
    @Test
    void shortlistLayout_rulesAreTheCachedPrefix_cardsArePerCall_hashIsTheFullCatalogue() {
        List<com.noticedesk.api.model.matching.IssueCard> cards = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            cards.add(new com.noticedesk.api.model.matching.IssueCard("T-00" + i, 1, "Title " + i, "summary", List.of(), null, null,
                    List.of(i <= 4 ? "scn_73" : "scn_74"), List.of(i <= 3 ? "Section 16(2)(aa)" : "Section 54"), List.of(),
                    null, "active", null, null, null));
        }
        CatalogueService catalogue = Mockito.spy(new CatalogueService(null, props, om));
        Mockito.doReturn(cards).when(catalogue).getActiveCards();
        IssueMatchingAgent agent = new IssueMatchingAgent(factory, catalogue, pricing, props, null, om);
        org.mockito.ArgumentCaptor<String> system = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.when(llm.generateWithEffort(system.capture(), user.capture(), Mockito.eq(SONNET), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(MATCHING_JSON, SONNET, "anthropic", 600, 1500, "end_turn", 5000, 0));
        String notice = "Show Cause Notice under Section 73\n1. ITC denied under Section 16(2)(aa).";

        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, inr("100"), pricing);
        MatchingResult r = agent.matchNoticeWithOcrText(notice, "scn_73", UUID.randomUUID(), UUID.randomUUID(), budget);

        assertEquals("labels", r.shortlist().level(), "no title agent wired: labels only, no warn");
        assertEquals("off", r.shortlist().aiStatus());
        assertEquals(List.of("T-001", "T-002", "T-003"), r.shortlist().cardIds());
        assertFalse(system.getValue().contains("CARD_ID:"), "no card in the cached system prompt");
        assertTrue(user.getValue().contains("Total Cards: 3"), user.getValue());
        assertTrue(user.getValue().contains("CARD_ID: T-003") && !user.getValue().contains("CARD_ID: T-004"));
        assertTrue(user.getValue().indexOf("CARD_ID:") < user.getValue().indexOf("FULL NOTICE OCR TEXT"), "cards before the notice");
        assertEquals(catalogue.getCatalogueHash(catalogue.renderCatalogueCompactText(cards)), r.catalogueHash(), "hash of all 6 cards");
        // the budget priced and recorded the rules alone as the cacheable prefix
        assertTrue(pricing.promptCache().isWarm(SONNET, PromptCacheRegistry.hash(system.getValue())));
        assertEquals(5000, pricing.promptCache().measuredTokens(SONNET, PromptCacheRegistry.hash(system.getValue())));

        assertFalse(user.getValue().contains("KEYWORDS:"), "a shortlist carries no keywords line");

        // a shortlist at level full (no stage filter, no label hit): the catalogue goes back into the cached system prompt
        MatchingResult full = agent.matchNoticeWithOcrText("A letter with no references", "unknown", UUID.randomUUID(),
                UUID.randomUUID(), budget);
        assertTrue(full.shortlist().isFull());
        assertTrue(system.getValue().contains("Total Cards: 6"));
        assertTrue(system.getValue().contains("KEYWORDS:"), "the full catalogue keeps its keywords");
        assertFalse(user.getValue().contains("CARD_ID:"));

        // shortlist off: no shortlist record, the whole catalogue in the system prompt
        props.getMatching().getShortlist().setEnabled(false);
        MatchingResult off = agent.matchNoticeWithOcrText(notice, "scn_73", UUID.randomUUID(), UUID.randomUUID(), budget);
        assertNull(off.shortlist());
        assertTrue(system.getValue().contains("Total Cards: 6"));
        assertEquals(r.catalogueHash(), off.catalogueHash());
    }

    private IssueMatchingAgent matcher() {
        CatalogueService catalogue = Mockito.mock(CatalogueService.class);
        Mockito.when(catalogue.getActiveCards()).thenReturn(List.of());
        Mockito.when(catalogue.renderCatalogueCompactText(Mockito.any())).thenReturn(CATALOGUE);
        return new IssueMatchingAgent(factory, catalogue, Mockito.mock(ApiUsageLogService.class), props, null, om);
    }
}
