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
    private static final String SYSTEM_START = "You are given a GST department document";

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
        assertTrue(system.startsWith(SYSTEM_START + " and a numbered list of issue cards (id and one-line title)."), system);
        assertTrue(system.contains("Be generous: include a card when in doubt; the full cards will be read later."));
        assertTrue(system.contains("Return JSON {\"card_ids\": [...]}."));
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
    void liveCallUsesLowEffort1500TokensTheCacheAndTheShortlistStep() {
        answer("{\"card_ids\": [\"NDK-003\", \"NDK-001\"]}");

        ShortlistTitleAgent.Picks picks = agent().pickCards(CARDS, "scn_73", NOTICE, UUID.randomUUID(), null);

        assertEquals(List.of("NDK-003", "NDK-001"), List.copyOf(picks.cardIds()));
        assertEquals(MODEL, picks.model(), "no shortlist model: the matching model");
        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> effort = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llm).generateWithEffort(Mockito.startsWith(SYSTEM_START), Mockito.startsWith("NOTICE TYPE: scn_73"),
                Mockito.eq(MODEL), maxTokens.capture(), effort.capture(), Mockito.eq(true));
        assertEquals(1500, maxTokens.getValue());
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
        answer("I think NDK-001 applies.");
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
