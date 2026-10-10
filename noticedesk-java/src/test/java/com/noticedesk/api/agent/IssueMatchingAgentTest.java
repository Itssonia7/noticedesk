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
        Mockito.when(mockLlm.generateWithPromptCaching(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyDouble(), Mockito.anyBoolean()))
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
        Mockito.when(mockLlm.generateWithPromptCaching(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyDouble(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("Not JSON output at all", "claude-sonnet-5-5", "anthropic", 50, 10, "end_turn", 0, 0));

        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        Mockito.when(llmFactory.getLlmForAgent("matching")).thenReturn(mockLlm);

        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, Mockito.mock(ApiUsageLogService.class), props, null, objectMapper);

        IssueMatchingAgent.MatchingResult result = agent.matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertNotNull(result);
        assertEquals(1, result.issues().size());
        assertEquals("none", result.issues().get(0).status());
        assertTrue(result.flags().contains("matching_failed"), "Must contain matching_failed flag on 2nd failure");
        Mockito.verify(mockLlm, Mockito.times(2)).generateWithPromptCaching(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyDouble(), Mockito.anyBoolean());
    }
}
