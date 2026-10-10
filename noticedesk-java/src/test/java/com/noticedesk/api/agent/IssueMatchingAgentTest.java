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
