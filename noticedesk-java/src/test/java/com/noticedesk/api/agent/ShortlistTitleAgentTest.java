package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.service.matching.CardShortlister;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.testsupport.LiveRunGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shortlist AI title pass: prompt layout, parser, call settings, degrade on failure / refusal / budget, fatal errors
 * passed on, and its picks united into the matcher's shortlist (mocked provider, no live calls).
 */
class ShortlistTitleAgentTest {

    private static final String MODEL = "test-matching-model";
    private static final String SYSTEM_START = "# Shortlist title pass (v1.1)";

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private LlmProvider llm;
    private LlmFactory factory;
    private ApiUsageLogService usage;

    private static IssueCard card(String id, String title, List<String> stages, List<String> keywords) {
        return new IssueCard(id, 1, title, "summary", List.of(), null, null, stages, keywords, List.of(), null, "active",
                null, null, null);
    }

    private static final List<IssueCard> CARDS = List.of(
            card("NDK-002", "ITC not in GSTR-2B (Section 16(2)(aa))", List.of("scn_73"), List.of("Section 16(2)(aa)")),
            card("NDK-001", "Vague  show cause notice:\nno grounds stated", List.of("generic_scn"), List.of("vague scn")),
            card("NDK-003", "Section 16(5) saves the credit", List.of("scn_73"), List.of("Section 16(5)")),
            card("NDK-004", "Interest under Section 50", List.of("scn_73"), List.of("Section 50")),
            card("NDK-005", "Appeal against a DRC-07 order", List.of("order_appeal"), List.of("DRC-07")));

    private static final String NOTICE = "Show Cause Notice under Section 73\n1. ITC denied under Section 16(4) and Section 16(2)(aa).";

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching(MODEL);
        AppProperties.Pricing.ModelPrice price = new AppProperties.Pricing.ModelPrice();
        price.setInputPerMillion(2.0);
        price.setOutputPerMillion(10.0);
        price.setCacheReadPerMillion(0.2);
        price.setCacheWritePerMillion(2.5);
        props.getPricing().getModels().put(MODEL, price);
        llm = Mockito.mock(LlmProvider.class);
        factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent(Mockito.anyString())).thenReturn(llm);
        usage = Mockito.mock(ApiUsageLogService.class);
    }

    private ShortlistTitleAgent agent() {
        return new ShortlistTitleAgent(props, om, usage, factory);
    }

    private void answer(String json) {
        Mockito.when(llm.generateWithEffort(Mockito.startsWith(SYSTEM_START), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean())).thenReturn(new LlmResponse(json, MODEL, "anthropic", 4000, 40));
    }

    // ------------------------------------------------------------------ prompt and parser

    @Test
    void systemPromptIsTheInstructionsPlusOneTitleLinePerCardInIdOrder() {
        String system = agent().systemPrompt(CARDS);
        assertTrue(system.startsWith(SYSTEM_START + "\n\nYou are given a GST department document and a numbered list of issue cards"
                + " (id and one-line title)."), system);
        assertTrue(system.contains("Be generous: include a card when in doubt; the full cards will be read later."));
        assertTrue(system.contains("never leave one out to keep the list short"));
        assertTrue(system.contains("Your answer must be ONLY this JSON object: {\"card_ids\": [\"NDK-001\", \"NDK-002\"]}"));
        assertTrue(system.contains("card ids only: no titles, no reasons"));
        assertTrue(system.contains("no markdown fences"));
        assertTrue(system.endsWith("ISSUE CARDS (id | title):\n"
                + "NDK-001 | Vague show cause notice: no grounds stated\n"
                + "NDK-002 | ITC not in GSTR-2B (Section 16(2)(aa))\n"
                + "NDK-003 | Section 16(5) saves the credit\n"
                + "NDK-004 | Interest under Section 50\n"
                + "NDK-005 | Appeal against a DRC-07 order\n"), system);
        assertEquals(system, agent().systemPrompt(new ArrayList<>(CARDS).reversed()), "same prefix whatever the load order (cacheable)");
        assertFalse(system.contains("NOTICE"), "no notice text in the cached prefix");
    }

    @Test
    void userMessageIsTheNoticeTypeAndTheCappedText() {
        props.getDrafting().setOcrExcerptChars(12);
        assertEquals("NOTICE TYPE: scn_73\n\nDOCUMENT TEXT:\nShow Cause N", agent().userPrompt("scn_73", NOTICE));
        assertTrue(agent().userPrompt(null, null).startsWith("NOTICE TYPE: unknown"));
    }

    @Test
    void parserKeepsCatalogueIdsOnlyInModelOrder() {
        Set<String> ids = Set.of("NDK-001", "NDK-002", "NDK-003");
        assertEquals(List.of("NDK-003", "NDK-001"),
                List.copyOf(agent().parseOrThrow("```json\n{\"card_ids\": [\"NDK-003\", \" NDK-001 \", \"NDK-999\", \"NDK-003\", null]}\n```", ids)));
        assertTrue(agent().parseOrThrow("{\"card_ids\": []}", ids).isEmpty(), "an empty list is a valid answer");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> agent().parseOrThrow("{\"cards\": [\"NDK-001\"]}", ids));
        assertTrue(e.getMessage().contains("'card_ids' is missing"), e.getMessage());
    }

    // ------------------------------------------------------------------ the call

    @Test
    void tolerantParserIdsOnlyFencedAndRegexFallback() {
        Set<String> ids = Set.of("NDK-001", "NDK-002", "NDK-003", "NDK-AI-001");
        ShortlistTitleAgent.Parsed plain = agent().parseTolerant("{\"card_ids\": [\"NDK-002\", \"NDK-AI-001\"]}", ids);
        assertEquals(List.of("NDK-002", "NDK-AI-001"), List.copyOf(plain.ids()));
        assertFalse(plain.fallback());
        ShortlistTitleAgent.Parsed fenced = agent().parseTolerant("```json\n{\"card_ids\": [\"NDK-003\"]}\n```", ids);
        assertEquals(Set.of("NDK-003"), fenced.ids());
        assertFalse(fenced.fallback(), "a fenced object is still JSON");
        ShortlistTitleAgent.Parsed broken = agent().parseTolerant(
                "{\"card_ids\": [\"NDK-003\" (Section 16(5)), \"NDK-999\", NDK-001, \"NDK-003\"", ids);
        assertEquals(List.of("NDK-003", "NDK-001"), List.copyOf(broken.ids()), "catalogue ids by regex, first-seen order");
        assertTrue(broken.fallback());
        ShortlistTitleAgent.Parsed wrongKey = agent().parseTolerant("{\"cards\": [{\"id\": \"NDK-002\", \"title\": \"t\"}]}", ids);
        assertEquals(Set.of("NDK-002"), wrongKey.ids());
        assertTrue(wrongKey.fallback());
        assertThrows(IllegalArgumentException.class, () -> agent().parseTolerant("no ids at all", ids));
        assertThrows(IllegalArgumentException.class, () -> agent().parseTolerant("NDK-999 only", ids), "ids outside the catalogue");
    }

    @Test
    void regexFallbackPicksAreUsedAndMarked() {
        answer("Cards: NDK-003, NDK-001");
        ShortlistTitleAgent.Picks picks = agent().pickCards(CARDS, "scn_73", NOTICE, null, null);
        assertEquals(List.of("NDK-003", "NDK-001"), List.copyOf(picks.cardIds()));
        assertTrue(picks.parseFallback());
        Mockito.verify(llm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void maxTokensComeFromTheShortlistSettings() {
        assertEquals(6000, props.maxTokensForAgent(ShortlistTitleAgent.AGENT_KEY, false));
        assertEquals(9000, props.maxTokensForAgent(ShortlistTitleAgent.AGENT_KEY, true));
        assertEquals(props.getLlm().maxTokensForAgent("matching", false), props.maxTokensForAgent("matching", false));
        AppProperties yml = com.noticedesk.api.testsupport.TestAppProperties.fromApplicationYml();
        if (System.getenv("MATCHING_SHORTLIST_TITLE_MAX_TOKENS") == null) {
            assertEquals(6000, yml.maxTokensForAgent(ShortlistTitleAgent.AGENT_KEY, false));
        }
        if (System.getenv("MATCHING_SHORTLIST_TITLE_RETRY_MAX_TOKENS") == null) {
            assertEquals(9000, yml.maxTokensForAgent(ShortlistTitleAgent.AGENT_KEY, true));
        }

        props.getMatching().getShortlist().setTitleMaxTokens(7000);
        props.getMatching().getShortlist().setTitleRetryMaxTokens(11000);
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(truncated(), new LlmResponse("{\"card_ids\": [\"NDK-002\"]}", MODEL, "anthropic", 4000, 900));
        assertEquals(Set.of("NDK-002"), agent().pickCards(CARDS, "scn_73", NOTICE, null, null).cardIds());
        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(llm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), maxTokens.capture(),
                Mockito.any(), Mockito.anyBoolean());
        assertEquals(List.of(7000, 11000), maxTokens.getAllValues());
    }

    private static LlmResponse truncated() {
        return new LlmResponse("{\"card_ids\": [\"NDK-0", MODEL, "anthropic", 4000, 6000, "max_tokens");
    }

    @Test
    void aTruncatedCallRepairedByTheRetryIsNotCountedByTheFailureGuard() {
        ApiUsageLogService real = new ApiUsageLogService(null, props);
        LiveRunGuard guard = new LiveRunGuard(name -> null);
        guard.attach(real);
        List<ApiUsageLogService.CallOutcome> outcomes = new ArrayList<>();
        real.addOutcomeListener(outcomes::add);
        usage = real;
        LlmResponse ok = new LlmResponse("{\"card_ids\": [\"NDK-002\"]}", MODEL, "anthropic", 4000, 900);

        // notice 1: truncated, then usable: one usable outcome, no failure counted
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(truncated(), ok);
        assertNotNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        assertEquals(List.of(new ApiUsageLogService.CallOutcome("shortlist", 2, true, null)), outcomes);
        assertEquals(0, guard.consecutiveFailures());

        // notice 2: truncated twice: the pair is one failure, the run goes on (labels only)
        Mockito.reset(llm);
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(truncated());
        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        assertEquals(new ApiUsageLogService.CallOutcome("shortlist", 2, false, LlmResponse.FAILURE_MAX_TOKENS), outcomes.get(1));
        assertEquals(1, guard.consecutiveFailures());

        // notice 3 fails as well: two failed notices in a row stop the run
        assertThrows(LlmFatalException.class, () -> agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        Mockito.verify(llm, Mockito.times(4)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void liveCallUsesLowEffort6000TokensTheCacheAndTheShortlistStep() {
        answer("{\"card_ids\": [\"NDK-003\", \"NDK-001\"]}");

        ShortlistTitleAgent.Picks picks = agent().pickCards(CARDS, "scn_73", NOTICE, UUID.randomUUID(), null);

        assertEquals(List.of("NDK-003", "NDK-001"), List.copyOf(picks.cardIds()));
        assertEquals(MODEL, picks.model(), "no shortlist model: the matching model");
        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> effort = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llm).generateWithEffort(Mockito.startsWith(SYSTEM_START), Mockito.startsWith("NOTICE TYPE: scn_73"),
                Mockito.eq(MODEL), maxTokens.capture(), effort.capture(), Mockito.eq(true));
        assertEquals(6000, maxTokens.getValue());
        assertEquals("low", effort.getValue());
        Mockito.verify(factory).getLlmForAgent("shortlist");
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.eq(MODEL), Mockito.eq("shortlist"), Mockito.eq(1), Mockito.any(), Mockito.any());
        assertEquals("shortlist", ShortlistTitleAgent.AGENT_KEY);
    }

    @Test
    void ownModelWhenSet() {
        props.getLlm().setModelShortlist("test-shortlist-model");
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("test-shortlist-model"), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean())).thenReturn(new LlmResponse("{\"card_ids\": [\"NDK-002\"]}", "test-shortlist-model", "anthropic", 10, 5));
        assertEquals(Set.of("NDK-002"), agent().pickCards(CARDS, "scn_73", NOTICE, null, null).cardIds());
        assertEquals("test-shortlist-model", props.modelForAgent("shortlist", "anthropic"));
    }

    @Test
    void unusableOutputTwiceDegradesToNull() {
        answer("I think the first card applies.");
        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        Mockito.verify(llm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void refusalAndCallErrorsDegradeToNullWithoutRetry() {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("", MODEL, "anthropic", 10, 0, "refusal"));
        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));

        Mockito.reset(llm);
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenThrow(new RuntimeException("timeout"));
        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        Mockito.verify(llm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void budgetRefusalMakesNoCall() {
        answer("{\"card_ids\": [\"NDK-001\"]}");
        NoticeCallBudget tiny = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal("0.0001"), new ApiUsageLogService(null, props));

        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, tiny));

        Mockito.verifyNoInteractions(llm);
        assertEquals(1, tiny.budgetRefusals());
    }

    @Test
    void billingOrAuthErrorsArePassedOn() {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenThrow(new LlmFatalException("credit balance too low"));
        assertThrows(LlmFatalException.class, () -> agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
    }

    @Test
    void notAvailableInStubModeOrWithoutAModel() {
        assertTrue(agent().available());
        props.getLlm().setProviderPrimary("stub");
        assertFalse(agent().available());
        assertNull(agent().pickCards(CARDS, "scn_73", NOTICE, null, null));
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("");
        assertFalse(agent().available());
        assertFalse(new ShortlistTitleAgent(props, om, usage, null).available());
        Mockito.verifyNoInteractions(llm);
    }

    // ------------------------------------------------------------------ inside the matcher

    private static final String MATCHING_JSON = """
            {"notice": {"notice_number": "N-1"}, "issues": [{"issue_no": 1, "status": "full", "kind": "demand",
              "card_id": "NDK-002", "why": "w", "facts": {}}], "para_map": {"1": [1]}}
            """;

    private IssueMatchingAgent matcher() {
        CatalogueService catalogue = Mockito.spy(new CatalogueService(null, props, om));
        Mockito.doReturn(CARDS).when(catalogue).getActiveCards();
        Mockito.when(llm.generateWithEffort(Mockito.startsWith("# System Prompt"), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean())).thenReturn(new LlmResponse(MATCHING_JSON, MODEL, "anthropic", 100, 100));
        IssueMatchingAgent m = new IssueMatchingAgent(factory, catalogue, usage, props, null, om);
        m.setShortlistTitleAgent(agent());
        return m;
    }

    @Test
    void matcherUnitesTheAiPicksWithTheLabelPicks() {
        answer("{\"card_ids\": [\"NDK-001\", \"NDK-003\"]}");
        IssueMatchingAgent m = matcher();
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);

        MatchingResult r = m.matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null);

        CardShortlister.Shortlist s = r.shortlist();
        assertEquals(CardShortlister.LEVEL_HYBRID, s.level());
        assertEquals(CardShortlister.AI_USED, s.aiStatus());
        assertEquals(Set.of("NDK-002"), s.labelPicks(), "Section 16(2)(aa) is the only label the notice cites");
        assertEquals(Set.of("NDK-001", "NDK-003"), s.aiPicks(), "the defence and procedural cards come from the title pass");
        assertEquals(List.of("NDK-002", "NDK-001", "NDK-003"), s.cardIds());
        Mockito.verify(llm).generateWithEffort(Mockito.startsWith("# System Prompt"), user.capture(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean());
        assertTrue(user.getValue().contains("CARD_ID: NDK-001") && user.getValue().contains("CARD_ID: NDK-003"));
        assertFalse(user.getValue().contains("CARD_ID: NDK-004"));
        assertFalse(user.getValue().contains("KEYWORDS:"));
    }

    @Test
    void matcherMarksAFailedTitlePassAsSkippedAndKeepsTheLabels() {
        answer("not json");
        MatchingResult r = matcher().matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null);
        assertTrue(r.shortlist().aiSkipped());
        assertEquals(CardShortlister.LEVEL_STAGE, r.shortlist().level(), "one label pick: the stage-pool floor");
    }

    @Test
    void matcherMarksRegexFallbackPicks() {
        answer("NDK-001 and NDK-003 apply");
        MatchingResult r = matcher().matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null);
        assertEquals(CardShortlister.AI_USED, r.shortlist().aiStatus());
        assertEquals(Set.of("NDK-001", "NDK-003"), r.shortlist().aiPicks());
        assertTrue(r.shortlist().aiParseFallback());
    }

    @Test
    void matcherMakesNoTitleCallWhenTheSwitchIsOffOrWhenWidening() {
        answer("{\"card_ids\": [\"NDK-001\"]}");
        props.getMatching().getShortlist().setAiTitles(false);
        MatchingResult off = matcher().matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null);
        assertEquals(CardShortlister.AI_OFF, off.shortlist().aiStatus());

        props.getMatching().getShortlist().setAiTitles(true);
        MatchingResult widened = matcher().matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null, true);
        assertEquals(CardShortlister.LEVEL_STAGE, widened.shortlist().level());
        assertEquals(List.of("NDK-002", "NDK-001", "NDK-003", "NDK-004"), widened.shortlist().cardIds(), "the scn_73 stage pool");
        Mockito.verify(llm, Mockito.never()).generateWithEffort(Mockito.startsWith(SYSTEM_START), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void aFullMatchOnATitlesOnlyCardIsLoweredToPartial() {
        List<IssueCard> cards = new ArrayList<>(CARDS);
        cards.set(0, new IssueCard("NDK-002", 1, "ITC not in GSTR-2B", "summary", List.of(), null, null, List.of("scn_73"),
                List.of("Section 16(2)(aa)"), List.of("Not this if an appeal (use NDK-005)"), null, "active", null, null, null));
        CatalogueService catalogue = Mockito.spy(new CatalogueService(null, props, om));
        Mockito.doReturn(cards).when(catalogue).getActiveCards();
        String json = MATCHING_JSON.replace("\"card_id\": \"NDK-002\"", "\"card_id\": \"NDK-005\"");
        Mockito.when(llm.generateWithEffort(Mockito.startsWith("# System Prompt"), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.anyBoolean())).thenReturn(new LlmResponse(json, MODEL, "anthropic", 100, 100));
        answer("{\"card_ids\": [\"NDK-001\", \"NDK-003\"]}");
        IssueMatchingAgent m = new IssueMatchingAgent(factory, catalogue, usage, props, null, om);
        m.setShortlistTitleAgent(agent());

        MatchingResult r = m.matchNoticeWithOcrText(NOTICE, "scn_73", UUID.randomUUID(), UUID.randomUUID(), null);

        assertEquals(List.of("NDK-005"), r.shortlist().titlesOnlyIds());
        assertEquals("partial", r.issues().get(0).status());
        assertEquals("NDK-005", r.issues().get(0).primaryCardId());
        assertTrue(r.issues().get(0).flags().contains(IssueMatchingAgent.FLAG_TITLES_ONLY_PRIMARY));
    }
}
