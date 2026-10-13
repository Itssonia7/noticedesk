package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.matching.CatalogueService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IssueMatchingAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testUnknownCardIdDowngradesIssueToNoneWithFlag() {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("claude-sonnet-5-5");

        CatalogueService catalogueService = Mockito.mock(CatalogueService.class);
        IssueCard validCard = new IssueCard("CARD-001", 1, "Valid Title", "Summary", List.of(), null, null, List.of(), List.of(), List.of(), null, "active", null, null, null);
        Mockito.when(catalogueService.getActiveCards()).thenReturn(List.of(validCard));
        Mockito.when(catalogueService.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue Text");
        Mockito.when(catalogueService.getCatalogueHash(Mockito.any())).thenReturn("hash-123");

        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        String mockJsonResponse = """
                {
                  "notice": {"notice_number": "SCN-101"},
                  "issues": [
                    {
                      "issue_no": 1,
                      "status": "full",
                      "card_ids": ["CARD-NONEXISTENT-999"],
                      "why": "Matching fake card",
                      "facts": {"period": "2023-24"}
                    }
                  ],
                  "para_map": {}
                }
                """;
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(mockJsonResponse, "claude-sonnet-5-5", "anthropic", 100, 50, "end_turn", 0, 0));

        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        Mockito.when(llmFactory.getLlmForAgent("matching")).thenReturn(mockLlm);

        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, Mockito.mock(ApiUsageLogService.class), props, null, objectMapper);

        IssueMatchingAgent.MatchingResult result = agent.matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertNotNull(result);
        assertEquals(1, result.issues().size());
        IssueMatchingAgent.MatchedIssue issue = result.issues().get(0);

        assertEquals("none", issue.status(), "Status must be downgraded to 'none' when card_id is unknown");
        assertTrue(issue.flags().contains("unknown_card_id"), "Must add 'unknown_card_id' flag");
        assertTrue(issue.cardIds().isEmpty(), "Unknown card_id must be removed from cardIds list");
    }

    @Test
    void testInvalidJsonTriggersRetryAndReturnsFallbackOnSecondFailure() {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("claude-sonnet-5-5");

        CatalogueService catalogueService = Mockito.mock(CatalogueService.class);
        Mockito.when(catalogueService.getActiveCards()).thenReturn(List.of());
        Mockito.when(catalogueService.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue Text");

        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        // Invalid non-JSON output
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("Not JSON output at all", "claude-sonnet-5-5", "anthropic", 50, 10, "end_turn", 0, 0));

        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        Mockito.when(llmFactory.getLlmForAgent("matching")).thenReturn(mockLlm);

        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, Mockito.mock(ApiUsageLogService.class), props, null, objectMapper);

        IssueMatchingAgent.MatchingResult result = agent.matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertNotNull(result);
        assertEquals(1, result.issues().size());
        assertEquals("none", result.issues().get(0).status());
        assertTrue(result.flags().contains("matching_failed"), "Must contain matching_failed flag on 2nd failure");
        Mockito.verify(mockLlm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    // ---------------------------------------------------------------- v2 / v1 output shapes and self-check flags

    private IssueMatchingAgent agentReturning(String json, String... activeCardIds) {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("claude-sonnet-5-5");
        CatalogueService catalogueService = Mockito.mock(CatalogueService.class);
        java.util.List<IssueCard> cards = new java.util.ArrayList<>();
        for (String id : activeCardIds) {
            cards.add(new IssueCard(id, 1, "Title " + id, "Summary", List.of(), null, null, List.of(), List.of(), List.of(), null, "active", null, null, null));
        }
        Mockito.when(catalogueService.getActiveCards()).thenReturn(cards);
        Mockito.when(catalogueService.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue Text");
        Mockito.when(catalogueService.getCatalogueHash(Mockito.any())).thenReturn("hash-123");
        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse(json, "claude-sonnet-5-5", "anthropic", 100, 50, "end_turn", 0, 0));
        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        Mockito.when(llmFactory.getLlmForAgent("matching")).thenReturn(mockLlm);
        return new IssueMatchingAgent(llmFactory, catalogueService, Mockito.mock(ApiUsageLogService.class), props, null, objectMapper);
    }

    private IssueMatchingAgent.MatchingResult run(IssueMatchingAgent agent) {
        return agent.matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void v2Shape_primaryInCardIds_alternatesSeparate_noFlags() {
        String json = """
                {"notice": {"notice_number": "SCN-1"},
                 "grounds": [{"ground_no": 1, "label": "ITC mismatch", "paras": ["3"]},
                             {"ground_no": 2, "label": "RCM on GTA", "paras": ["4"]}],
                 "issues": [
                   {"issue_no": 1, "status": "full", "card_id": "NDK-006", "alternates": ["NDK-007", "NDK-008"], "why": "a", "notice_paras": ["3"]},
                   {"issue_no": 2, "status": "none", "card_id": null, "alternates": [], "why": "b", "notice_paras": ["4"]}],
                 "para_map": {"3": [1], "4": [2]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007", "NDK-008"));

        assertTrue(result.flags().isEmpty(), result.flags().toString());
        assertEquals(2, result.issues().size());
        IssueMatchingAgent.MatchedIssue first = result.issues().get(0);
        assertEquals("full", first.status());
        assertEquals(List.of("NDK-006"), first.cardIds(), "cardIds holds ONLY the primary");
        assertEquals("NDK-006", first.primaryCardId());
        assertEquals(List.of("NDK-007", "NDK-008"), first.alternateCardIds());
        assertTrue(first.flags().isEmpty());
        IssueMatchingAgent.MatchedIssue second = result.issues().get(1);
        assertEquals("none", second.status());
        assertTrue(second.cardIds().isEmpty());
        assertNull(second.primaryCardId());
        assertTrue(second.alternateCardIds().isEmpty());
        assertTrue(second.flags().isEmpty());
    }

    @Test
    void v1Shape_firstCardIdIsPrimary_restAreAlternates() {
        String json = """
                {"notice": {"notice_number": "SCN-1"},
                 "issues": [{"issue_no": 1, "status": "partial", "card_ids": ["NDK-006", "NDK-007", "NDK-008"], "why": "a"}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007", "NDK-008"));

        IssueMatchingAgent.MatchedIssue issue = result.issues().get(0);
        assertEquals("partial", issue.status());
        assertEquals(List.of("NDK-006"), issue.cardIds());
        assertEquals(List.of("NDK-007", "NDK-008"), issue.alternateCardIds());
        assertTrue(issue.flags().isEmpty());
        assertTrue(result.flags().isEmpty(), "no grounds list in v1 output: no issue_count_mismatch check");
    }

    @Test
    void unknownAlternate_isDroppedSilently_primaryRepeatedAsAlternateIsDropped_maxTwoKept() {
        String json = """
                {"notice": {},
                 "issues": [{"issue_no": 1, "status": "full", "card_id": "NDK-006",
                             "alternates": ["NDK-999", "NDK-006", "NDK-007", "NDK-008", "NDK-009"], "why": "a"}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007", "NDK-008", "NDK-009"));

        IssueMatchingAgent.MatchedIssue issue = result.issues().get(0);
        assertEquals("full", issue.status(), "an unknown alternate never downgrades the issue");
        assertEquals(List.of("NDK-006"), issue.cardIds());
        assertEquals(List.of("NDK-007", "NDK-008"), issue.alternateCardIds());
        assertTrue(issue.flags().isEmpty());
    }

    @Test
    void unknownPrimary_v2Shape_becomesNoneWithFlag_alternatesKept() {
        String json = """
                {"notice": {},
                 "issues": [{"issue_no": 1, "status": "full", "card_id": "NDK-999", "alternates": ["NDK-007"], "why": "a"}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007"));

        IssueMatchingAgent.MatchedIssue issue = result.issues().get(0);
        assertEquals("none", issue.status());
        assertTrue(issue.cardIds().isEmpty());
        assertEquals(List.of(IssueMatchingAgent.FLAG_UNKNOWN_CARD_ID), issue.flags());
        assertEquals(List.of("NDK-007"), issue.alternateCardIds());
    }

    @Test
    void hedged_fullOrPartialWithoutPrimary_isFlaggedNotFailed() {
        String json = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "label": "x", "paras": ["1"]}, {"ground_no": 2, "label": "y", "paras": ["2"]}],
                 "issues": [
                   {"issue_no": 1, "status": "partial", "card_id": null, "alternates": ["NDK-006", "NDK-007"], "why": "unsure"},
                   {"issue_no": 2, "status": "full", "card_id": "", "alternates": [], "why": "blank id"}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007"));

        assertFalse(result.flags().contains("matching_failed"));
        assertEquals(2, result.issues().size());
        IssueMatchingAgent.MatchedIssue first = result.issues().get(0);
        assertEquals("partial", first.status(), "status is kept; the flag marks the hedge");
        assertTrue(first.cardIds().isEmpty());
        assertEquals(List.of("NDK-006", "NDK-007"), first.alternateCardIds());
        assertEquals(List.of(IssueMatchingAgent.FLAG_HEDGED), first.flags());
        assertEquals(List.of(IssueMatchingAgent.FLAG_HEDGED), result.issues().get(1).flags());
        assertTrue(result.flags().isEmpty(), "two grounds, two issues: no count mismatch");
    }

    @Test
    void issueCountMismatch_isAResultFlagNotAFailure() {
        String json = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "label": "x", "paras": ["1"]},
                             {"ground_no": 2, "label": "y", "paras": ["2"]},
                             {"ground_no": 3, "label": "z", "paras": ["3"]}],
                 "issues": [{"issue_no": 1, "status": "full", "card_id": "NDK-006", "alternates": [], "why": "a"},
                            {"issue_no": 2, "status": "none", "card_id": null, "alternates": [], "why": "b"}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006"));

        assertEquals(List.of(IssueMatchingAgent.FLAG_ISSUE_COUNT_MISMATCH), result.flags());
        assertEquals(2, result.issues().size());
        assertEquals("full", result.issues().get(0).status());
        assertTrue(result.issues().get(0).flags().isEmpty());
    }

    @Test
    void readCardChoice_bothShapes() throws Exception {
        var v2 = IssueMatchingAgent.readCardChoice(objectMapper.readTree("{\"card_id\": \" NDK-001 \", \"alternates\": [\"NDK-002\", \"\", null, 3]}"));
        assertEquals("NDK-001", v2.primary());
        assertEquals(List.of("NDK-002"), v2.alternates());

        var v2None = IssueMatchingAgent.readCardChoice(objectMapper.readTree("{\"card_id\": null, \"alternates\": []}"));
        assertNull(v2None.primary());
        assertTrue(v2None.alternates().isEmpty());

        var v1 = IssueMatchingAgent.readCardChoice(objectMapper.readTree("{\"card_ids\": [\"NDK-001\", \"NDK-002\"]}"));
        assertEquals("NDK-001", v1.primary());
        assertEquals(List.of("NDK-002"), v1.alternates());

        var v1Text = IssueMatchingAgent.readCardChoice(objectMapper.readTree("{\"card_ids\": \"NDK-001\"}"));
        assertEquals("NDK-001", v1Text.primary());

        var empty = IssueMatchingAgent.readCardChoice(objectMapper.readTree("{\"status\": \"none\"}"));
        assertNull(empty.primary());
        assertTrue(empty.alternates().isEmpty());
    }

    // ---------------------------------------------------------------- thinking-model stop reasons

    private static final String VALID_JSON = """
            {"notice": {"notice_number": "FAKE-SCN-1"}, "issues": [{"issue_no": 1, "status": "none", "card_ids": [], "why": "x"}]}
            """;

    private IssueMatchingAgent agentWith(LlmProvider llm, ApiUsageLogService usage) {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("claude-sonnet-5-5");
        props.getLlm().setEffortMatching("low");
        CatalogueService catalogueService = Mockito.mock(CatalogueService.class);
        Mockito.when(catalogueService.getActiveCards()).thenReturn(List.of());
        Mockito.when(catalogueService.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue Text");
        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        Mockito.when(llmFactory.getLlmForAgent("matching")).thenReturn(llm);
        return new IssueMatchingAgent(llmFactory, catalogueService, usage, props, null, objectMapper);
    }

    private static LlmResponse failedResponse(String stopReason, String failure, int out) {
        return new LlmResponse("", "claude-sonnet-5-5", "anthropic", 1000, out, stopReason, 0, 9000, failure);
    }

    @Test
    void maxTokensStop_isRetriedOnceWithHigherCap_andBothCallsAreLogged() {
        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(failedResponse("max_tokens", LlmResponse.FAILURE_MAX_TOKENS, 8000))
                .thenReturn(new LlmResponse(VALID_JSON, "claude-sonnet-5-5", "anthropic", 1000, 3000, "end_turn", 0, 9000));
        ApiUsageLogService usage = Mockito.mock(ApiUsageLogService.class);

        IssueMatchingAgent.MatchingResult result = agentWith(mockLlm, usage)
                .matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertFalse(result.flags().contains("matching_failed"));
        assertEquals("FAKE-SCN-1", result.notice().noticeNumber());
        Mockito.verify(mockLlm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("claude-sonnet-5-5"),
                Mockito.eq(8000), Mockito.eq("low"), Mockito.eq(true));
        Mockito.verify(mockLlm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("claude-sonnet-5-5"),
                Mockito.eq(16000), Mockito.eq("low"), Mockito.eq(true));
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.any(), Mockito.eq("matching"), Mockito.eq(1), Mockito.any(), Mockito.any());
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.any(), Mockito.eq("matching"), Mockito.eq(2), Mockito.any(), Mockito.any());
        assertEquals(11000, result.outputTokens(), "output tokens of the failed attempt are counted too");
    }

    @Test
    void refusal_isFailure_withoutRetry() {
        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(failedResponse("refusal", LlmResponse.FAILURE_REFUSAL, 40));
        ApiUsageLogService usage = Mockito.mock(ApiUsageLogService.class);

        IssueMatchingAgent.MatchingResult result = agentWith(mockLlm, usage)
                .matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertTrue(result.flags().contains("matching_failed"));
        Mockito.verify(mockLlm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(usage, Mockito.times(1)).logCall(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }

    @Test
    void noTextBlock_isFailure_andRetried() {
        LlmProvider mockLlm = Mockito.mock(LlmProvider.class);
        Mockito.when(mockLlm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(failedResponse("end_turn", LlmResponse.FAILURE_NO_TEXT, 2500));

        IssueMatchingAgent.MatchingResult result = agentWith(mockLlm, Mockito.mock(ApiUsageLogService.class))
                .matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertTrue(result.flags().contains("matching_failed"));
        Mockito.verify(mockLlm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }
}
