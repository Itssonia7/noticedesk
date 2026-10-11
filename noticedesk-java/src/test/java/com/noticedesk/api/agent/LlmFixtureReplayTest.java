package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.HighStakesDraftingAgent.HighStakesResult;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.ApiUsageLogService.CallOutcome;
import com.noticedesk.api.service.llm.ApiUsageLogService.UsageCall;
import com.noticedesk.api.service.llm.FixtureAnthropicServer;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.matching.CatalogueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline replay: each v7 agent runs end-to-end through the real Anthropic provider against recorded,
 * synthetic Messages API responses ({@code llm-fixtures/}, thinking block first, then text). No API call,
 * no cost. Catches regressions such as reading {@code content[0]} (the thinking block) or JSON broken by
 * unescaped quotes. Model ids are fixture ids, not real models.
 */
class LlmFixtureReplayTest {

    private static final String MATCHING = "fixture-matching-model";
    private static final String PARTIAL = "fixture-partial-model";
    private static final String NEW_ISSUE = "fixture-new-issue-model";
    private static final String HIGH_STAKES = "fixture-high-stakes-model";

    private final ObjectMapper om = new ObjectMapper();
    private FixtureAnthropicServer server;
    private AppProperties props;
    private ApiUsageLogService usage;
    private final List<UsageCall> calls = new ArrayList<>();
    private final List<CallOutcome> outcomes = new ArrayList<>();
    private LlmFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        server = new FixtureAnthropicServer();
        props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching(MATCHING);
        props.getLlm().setModelPartialDrafting(PARTIAL);
        props.getLlm().setModelNewIssue(NEW_ISSUE);
        props.getLlm().setModelHighStakes(HIGH_STAKES);
        props.getLlm().setEffortMatching("low");
        props.getLlm().setEffortPartialDrafting("medium");
        props.getLlm().setEffortNewIssue("medium");
        props.getLlm().setEffortHighStakes("high");
        usage = new ApiUsageLogService(null, props);
        usage.addCallListener(calls::add);
        usage.addOutcomeListener(outcomes::add);

        List<String> effortModels = List.of(MATCHING, PARTIAL, NEW_ISSUE, HIGH_STAKES);
        factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent(Mockito.anyString()))
                .thenAnswer(inv -> server.provider(props.modelForAgent(inv.getArgument(0), "anthropic"), effortModels));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private IssueMatchingAgent matcher() {
        CatalogueService catalogue = Mockito.mock(CatalogueService.class);
        IssueCard card2 = new IssueCard("CARD-002", 1, "Section 74 extended period", "summary", List.of(), null, null,
                List.of("scn_74"), List.of(), List.of(), null, "draft", null, null, null);
        Mockito.when(catalogue.getActiveCards()).thenReturn(List.of(card2));
        Mockito.when(catalogue.renderCatalogueCompactText(Mockito.any())).thenReturn("CATALOGUE (fixture)");
        Mockito.when(catalogue.getCatalogueHash(Mockito.any())).thenReturn("fixture-hash");
        return new IssueMatchingAgent(factory, catalogue, usage, props, null, om);
    }

    // ---------------------------------------------------------------- matching

    @Test
    void matching_readsTextBlockAfterThinking_fromFencedJson() throws Exception {
        server.reply("matching_notice02_partial.json");

        MatchingResult r = matcher().matchNoticeWithOcrText("synthetic notice text", "scn_74", UUID.randomUUID(), UUID.randomUUID());

        assertTrue(r.flags().isEmpty(), "no fallback flags: " + r.flags());
        assertEquals("SCN/DL/2021-22/740", r.notice().noticeNumber());
        assertEquals(500000.0, r.notice().totalDemandAmount());
        assertEquals(1, r.issues().size());
        MatchedIssue issue = r.issues().get(0);
        assertEquals("partial", issue.status());
        assertEquals(List.of("CARD-002"), issue.cardIds());
        assertEquals("2020-21", issue.facts().get("period"));
        assertEquals(5, r.paraMap().size());
        assertEquals(4200, r.inputTokens());
        assertEquals(9000, r.cacheWriteTokens());

        assertEquals(1, server.callCount());
        JsonNode req = server.requests().get(0);
        assertEquals(MATCHING, req.path("model").asText());
        assertEquals("low", req.path("output_config").path("effort").asText());
        assertFalse(req.has("temperature"));
        assertFalse(req.has("thinking"));
        assertEquals(List.of(true), outcomes.stream().map(CallOutcome::usable).toList());
    }

    @Test
    void matching_repairsUnescapedQuotesInsideStringValue() throws Exception {
        server.reply("matching_notice02_unescaped_quotes.json");

        MatchingResult r = matcher().matchNoticeWithOcrText("synthetic notice text", "scn_74", UUID.randomUUID(), UUID.randomUUID());

        assertTrue(r.flags().isEmpty(), "quotes must be repaired, not a fallback: " + r.missingOrDoubtful());
        assertEquals("partial", r.issues().get(0).status());
        assertTrue(r.issues().get(0).why().contains("\"willful suppression\""), r.issues().get(0).why());
        assertEquals(1, server.callCount(), "a repairable response needs no retry");
    }

    @Test
    void matching_maxTokens_retriesOnceWithHigherCap_andBothCallsAreBilled() throws Exception {
        server.reply("matching_max_tokens.json").reply("matching_notice02_partial.json");

        MatchingResult r = matcher().matchNoticeWithOcrText("synthetic notice text", "scn_74", UUID.randomUUID(), UUID.randomUUID());

        assertTrue(r.flags().isEmpty());
        assertEquals(2, server.callCount());
        assertEquals(props.getLlm().maxTokensForAgent("matching", false), server.requests().get(0).path("max_tokens").asInt());
        assertEquals(props.getLlm().maxTokensForAgent("matching", true), server.requests().get(1).path("max_tokens").asInt());
        assertEquals(2, calls.size(), "the truncated call is billed and logged too");
        assertEquals("max_tokens", calls.get(0).failureReason());
        assertEquals(List.of(false, true), outcomes.stream().map(CallOutcome::usable).toList());
    }

    @Test
    void matching_creditBalanceError_isFatal_noRetry() throws Exception {
        server.reply(400, "error_credit_balance.json");

        LlmFatalException e = assertThrows(LlmFatalException.class,
                () -> matcher().matchNoticeWithOcrText("synthetic notice text", "scn_74", UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(e.getMessage().contains("credit balance"), e.getMessage());
        assertEquals(1, server.callCount(), "a billing error is never retried");
    }

    @Test
    void matching_authError_isFatal_noRetry() throws Exception {
        server.reply(401, "error_authentication.json");

        assertThrows(LlmFatalException.class,
                () -> matcher().matchNoticeWithOcrText("synthetic notice text", "scn_74", UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(1, server.callCount());
    }

    // ---------------------------------------------------------------- writers

    private static MatchedNoticeInfo notice() {
        return new MatchedNoticeInfo("SCN/GJ/2023-24/707", null, "22-10-2023", null, "2023-24", null, 400000.0, null);
    }

    @Test
    void partialWriter_parsesSectionsCitationsAndDocuments() throws Exception {
        server.reply("partial_notice07.json");
        MatchedIssue issue = new MatchedIssue(1, "partial", List.of("CARD-006"), "w", List.of(), Map.of("period", "2023-24"), List.of("3", "4"), List.of());

        PartialDraftingResult r = new PartialDraftingAgent(props, om, usage, factory)
                .generatePartialDrafting(issue, notice(), null, "scn_73", UUID.randomUUID(), false, null);

        assertNotNull(r);
        assertEquals(3, r.sections().size());
        assertEquals(4, r.sections().get(0).section());
        assertTrue(r.sections().get(0).html().contains("Rule 46"));
        assertEquals(1, r.citations().size());
        assertEquals(2021, r.citations().get(0).year());
        assertEquals(2, r.documents().size());
        assertEquals(PARTIAL, r.model());
        assertEquals(PARTIAL, server.requests().get(0).path("model").asText());
        assertEquals("medium", server.requests().get(0).path("output_config").path("effort").asText());
    }

    @Test
    void newIssueWriter_repairsUnescapedQuotesInHtml() throws Exception {
        server.reply("new_issue_notice11_unescaped_quotes.json");
        MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of("period", "2023-24"), List.of("3", "5"), List.of());

        NewIssueDraftingResult r = new NewIssueDraftingAgent(props, om, usage, factory)
                .generateNewIssueDraft(issue, notice(), "synthetic notice text", "scn_73", UUID.randomUUID(), false, null);

        assertNotNull(r, "quote repair must make the response usable");
        assertTrue(r.section04Html().contains("casual taxable person"), r.section04Html());
        assertTrue(r.section05Html().contains("Para 5(b)"));
        assertEquals(List.of("Sales register for the exhibition", "Stall allotment letter"), r.documents());
        assertEquals(1, server.callCount(), "no retry needed");
        // the notice context is sent first as a cacheable block, the issue second
        JsonNode content = server.requests().get(0).path("messages").get(0).path("content");
        assertEquals(2, content.size());
        assertTrue(content.get(0).has("cache_control"));
    }

    @Test
    void newIssueWriter_inHighStakesMode_callsTheHighStakesModel() throws Exception {
        server.reply("new_issue_notice11_unescaped_quotes.json");
        MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of(), List.of(), List.of());

        NewIssueDraftingResult r = new NewIssueDraftingAgent(props, om, usage, factory)
                .generateNewIssueDraft(issue, notice(), "synthetic notice text", "scn_74", UUID.randomUUID(), true, null);

        assertNotNull(r);
        assertEquals(HIGH_STAKES, server.requests().get(0).path("model").asText());
        assertEquals("high", server.requests().get(0).path("output_config").path("effort").asText());
        assertEquals("high_stakes", calls.get(0).step());
    }

    @Test
    void highStakesWriter_parsesSections01And03() throws Exception {
        server.reply("high_stakes_notice02.json");

        HighStakesResult r = new HighStakesDraftingAgent(props, om, usage, factory)
                .generateHighStakesDraft(null, null, "scn_74", UUID.randomUUID(), null);

        assertNotNull(r);
        assertTrue(r.section01Html().contains("Section 74"));
        assertTrue(r.section03Html().contains("GSTR-3B"));
        assertEquals(HIGH_STAKES, r.model());
    }

    @Test
    void writer_maxTokensThenNothing_returnsNull_afterTwoBilledCalls() throws Exception {
        server.reply("matching_max_tokens.json").reply("matching_max_tokens.json");
        MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of(), List.of(), List.of());

        NewIssueDraftingResult r = new NewIssueDraftingAgent(props, om, usage, factory)
                .generateNewIssueDraft(issue, notice(), "synthetic notice text", "scn_73", UUID.randomUUID(), false, null);

        assertNull(r);
        assertEquals(2, server.callCount(), "at most two calls per issue");
        assertEquals(props.getLlm().maxTokensForAgent("new_issue", true), server.requests().get(1).path("max_tokens").asInt());
        assertEquals(List.of(false, false), outcomes.stream().map(CallOutcome::usable).toList());
    }

    @Test
    void writer_creditBalanceError_isThrownNotSwallowed() throws Exception {
        server.reply(400, "error_credit_balance.json");
        MatchedIssue issue = new MatchedIssue(1, "partial", List.of("CARD-006"), "w", List.of(), Map.of(), List.of(), List.of());

        assertThrows(LlmFatalException.class, () -> new PartialDraftingAgent(props, om, usage, factory)
                .generatePartialDrafting(issue, notice(), null, "scn_73", UUID.randomUUID(), false, null));
        assertEquals(1, server.callCount());
    }
}
