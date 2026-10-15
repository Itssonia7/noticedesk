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

        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(), "pre-v3.4 output: only defect_checks_missing");
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
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(),
                "no grounds list in v1 output: no issue_count_mismatch check; pre-v3.4 output carries only defect_checks_missing");
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
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(),
                "two grounds, two issues: no count mismatch (pre-v3.4 output: only defect_checks_missing)");
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

        assertEquals(List.of(IssueMatchingAgent.FLAG_ISSUE_COUNT_MISMATCH, IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(),
                "pre-v3.4 output (no defect_checks) also carries defect_checks_missing");
        assertEquals(2, result.issues().size());
        assertEquals("full", result.issues().get(0).status());
        assertTrue(result.issues().get(0).flags().isEmpty());
    }

    // ---------------------------------------------------------------- v3: issue kind and the defect_without_card flag

    @Test
    void v3Kind_isParsedPerIssue_andDefaultsToDemand() {
        String json = """
                {"notice": {"din": null},
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "ITC mismatch", "paras": ["3"]},
                             {"ground_no": 2, "kind": "defect", "label": "DIN missing", "paras": ["header"]},
                             {"ground_no": 3, "label": "no kind given", "paras": ["4"]},
                             {"ground_no": 4, "kind": "DEFECT ", "label": "unsigned order", "paras": ["header"]},
                             {"ground_no": 5, "kind": "something else", "label": "unknown kind", "paras": ["5"]}],
                 "issues": [
                   {"issue_no": 1, "kind": "demand", "status": "full", "card_id": "NDK-006", "alternates": [], "why": "a", "notice_paras": ["3"]},
                   {"issue_no": 2, "kind": "defect", "status": "full", "card_id": "NDK-141", "alternates": ["NDK-143"], "why": "b", "notice_paras": ["header"]},
                   {"issue_no": 3, "status": "partial", "card_id": "NDK-007", "alternates": [], "why": "c", "notice_paras": ["4"]},
                   {"issue_no": 4, "kind": "DEFECT ", "status": "partial", "card_id": "NDK-143", "alternates": [], "why": "d", "notice_paras": ["header"]},
                   {"issue_no": 5, "kind": "something else", "status": "none", "card_id": null, "alternates": [], "why": "e", "notice_paras": ["5"]}],
                 "para_map": {"3": [1], "4": [3], "5": [5]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007", "NDK-141", "NDK-143"));

        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(), "pre-v3.4 output: only defect_checks_missing");
        assertEquals(5, result.issues().size());
        IssueMatchingAgent.MatchedIssue demand = result.issues().get(0);
        assertEquals(IssueMatchingAgent.KIND_DEMAND, demand.kind());
        assertFalse(demand.isDefect());
        IssueMatchingAgent.MatchedIssue defect = result.issues().get(1);
        assertEquals(IssueMatchingAgent.KIND_DEFECT, defect.kind());
        assertTrue(defect.isDefect());
        assertEquals("full", defect.status());
        assertEquals(List.of("NDK-141"), defect.cardIds(), "cardIds holds ONLY the primary, for a defect too");
        assertEquals(List.of("NDK-143"), defect.alternateCardIds());
        assertEquals(List.of("header"), defect.noticeParas());
        assertTrue(defect.flags().isEmpty(), "a defect with a card gets no flag");
        assertEquals(IssueMatchingAgent.KIND_DEMAND, result.issues().get(2).kind(), "no kind (v1 / v2 output) = demand");
        assertEquals(IssueMatchingAgent.KIND_DEFECT, result.issues().get(3).kind(), "kind is trimmed and case-insensitive");
        assertTrue(result.issues().get(3).flags().isEmpty(), "a partial defect with a card gets no flag");
        assertEquals(IssueMatchingAgent.KIND_DEMAND, result.issues().get(4).kind(), "an unknown kind is demand");
        assertTrue(result.issues().get(4).flags().isEmpty(), "a demand issue with status none gets no defect flag");
    }

    @Test
    void v3Defect_withoutCard_getsTheInformationalFlag_statusStaysNone() {
        String json = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "kind": "defect", "label": "no hearing offered", "paras": ["6"]},
                             {"ground_no": 2, "kind": "defect", "label": "DIN missing", "paras": ["header"]}],
                 "issues": [
                   {"issue_no": 1, "kind": "defect", "status": "none", "card_id": null, "alternates": [], "why": "no card", "notice_paras": ["6"]},
                   {"issue_no": 2, "kind": "defect", "status": "full", "card_id": "NDK-999", "alternates": [], "why": "unknown card", "notice_paras": ["header"]}],
                 "para_map": {"6": [1]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-141"));

        assertFalse(result.flags().contains("matching_failed"));
        IssueMatchingAgent.MatchedIssue noCard = result.issues().get(0);
        assertEquals("none", noCard.status());
        assertTrue(noCard.isDefect());
        assertTrue(noCard.cardIds().isEmpty());
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_WITHOUT_CARD), noCard.flags());
        assertEquals("defect_without_card", IssueMatchingAgent.FLAG_DEFECT_WITHOUT_CARD);

        // an unknown primary on a defect: downgraded to none (unknown_card_id) and then also defect_without_card
        IssueMatchingAgent.MatchedIssue unknown = result.issues().get(1);
        assertEquals("none", unknown.status());
        assertTrue(unknown.isDefect());
        assertEquals(List.of(IssueMatchingAgent.FLAG_UNKNOWN_CARD_ID, IssueMatchingAgent.FLAG_DEFECT_WITHOUT_CARD), unknown.flags());
    }

    // ---------------------------------------------------------------- v3.2: client_facts_to_confirm and partial_on_client_fact

    @Test
    void v3_2ClientFactsToConfirm_areReadPerIssue_andThePartialOnClientFactFlagIsInformational() {
        String json = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "RCM on GTA", "paras": ["3"]},
                             {"ground_no": 2, "kind": "demand", "label": "ITC from non-filer", "paras": ["4"]},
                             {"ground_no": 3, "kind": "defect", "label": "DIN missing", "paras": ["header"]},
                             {"ground_no": 4, "kind": "demand", "label": "late fee", "paras": ["5"]}],
                 "issues": [
                   {"issue_no": 1, "kind": "demand", "status": "full", "card_id": "NDK-006", "alternates": [],
                    "why": "DECISIVE facts present (para 3).",
                    "client_facts_to_confirm": [" No consignment note was issued ", "", null, 7, "GTA has not opted for forward charge"],
                    "notice_paras": ["3"]},
                   {"issue_no": 2, "kind": "demand", "status": "partial", "card_id": "NDK-007", "alternates": [],
                    "why": "DECISIVE \\"supplier has not filed GSTR-3B\\": absent - the notice is silent; CLIENT fact recipient paid supplier not in notice.",
                    "client_facts_to_confirm": ["Recipient holds invoices and bank proof of payment"],
                    "notice_paras": ["4"]},
                   {"issue_no": 3, "kind": "defect", "status": "none", "card_id": null, "alternates": [], "why": "no card",
                    "client_facts_to_confirm": [], "notice_paras": ["header"]},
                   {"issue_no": 4, "kind": "demand", "status": "partial", "card_id": "NDK-006", "alternates": [],
                    "why": "DECISIVE \\"Section 47\\": contradicted - the notice cites Section 50 (para 5).",
                    "client_facts_to_confirm": "not a list", "notice_paras": ["5"]}],
                 "para_map": {"3": [1], "4": [2], "5": [4]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-007"));

        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), result.flags(), "pre-v3.4 output: only defect_checks_missing");
        assertEquals(4, result.issues().size());

        IssueMatchingAgent.MatchedIssue full = result.issues().get(0);
        assertEquals("full", full.status());
        assertEquals(List.of("No consignment note was issued", "GTA has not opted for forward charge"), full.clientFactsToConfirm(),
                "entries are trimmed; blank, null and non-text entries are dropped");
        assertTrue(full.flags().isEmpty(), "a full match never gets the partial_on_client_fact flag");

        IssueMatchingAgent.MatchedIssue partialOnClient = result.issues().get(1);
        assertEquals("partial", partialOnClient.status(), "the status is kept: the flag is informational");
        assertEquals(List.of("NDK-007"), partialOnClient.cardIds());
        assertEquals(List.of("Recipient holds invoices and bank proof of payment"), partialOnClient.clientFactsToConfirm());
        assertEquals(List.of(IssueMatchingAgent.FLAG_PARTIAL_ON_CLIENT_FACT), partialOnClient.flags());
        assertEquals("partial_on_client_fact", IssueMatchingAgent.FLAG_PARTIAL_ON_CLIENT_FACT);

        IssueMatchingAgent.MatchedIssue none = result.issues().get(2);
        assertTrue(none.clientFactsToConfirm().isEmpty());
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_WITHOUT_CARD), none.flags(), "status none: no client-fact flag");

        IssueMatchingAgent.MatchedIssue partialOnDecisive = result.issues().get(3);
        assertEquals("partial", partialOnDecisive.status());
        assertTrue(partialOnDecisive.clientFactsToConfirm().isEmpty(), "a non-array value reads as an empty list");
        assertTrue(partialOnDecisive.flags().isEmpty(), "a partial whose why names no CLIENT fact gets no flag");
    }

    @Test
    void v3_2FieldAbsent_v3_1Output_readsAsEmptyList_withNoFlag() {
        String json = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "x", "paras": ["1"]}],
                 "issues": [{"issue_no": 1, "kind": "demand", "status": "partial", "card_id": "NDK-006", "alternates": [],
                             "why": "DECISIVE \\"form DRC-01\\": absent - the document is an ASMT-10 (para 1).", "notice_paras": ["1"]}],
                 "para_map": {"1": [1]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006"));

        IssueMatchingAgent.MatchedIssue issue = result.issues().get(0);
        assertEquals("partial", issue.status());
        assertNotNull(issue.clientFactsToConfirm());
        assertTrue(issue.clientFactsToConfirm().isEmpty(), "no client_facts_to_confirm key (v3.1 output): empty list");
        assertTrue(issue.flags().isEmpty());
    }

    @Test
    void readClientFactsToConfirm_andThePartialOnClientFactHeuristic() throws Exception {
        assertEquals(List.of("a", "b"), IssueMatchingAgent.readClientFactsToConfirm(
                objectMapper.readTree("{\"client_facts_to_confirm\": [\" a \", \"b\", \"  \", null, 1]}")));
        assertTrue(IssueMatchingAgent.readClientFactsToConfirm(objectMapper.readTree("{\"client_facts_to_confirm\": null}")).isEmpty());
        assertTrue(IssueMatchingAgent.readClientFactsToConfirm(objectMapper.readTree("{\"client_facts_to_confirm\": \"x\"}")).isEmpty());
        assertTrue(IssueMatchingAgent.readClientFactsToConfirm(objectMapper.readTree("{\"status\": \"full\"}")).isEmpty());

        assertTrue(IssueMatchingAgent.isPartialOnClientFact("partial", "DECISIVE \"x\": absent - CLIENT fact"));
        assertTrue(IssueMatchingAgent.isPartialOnClientFact("Partial", "the card's CLIENT: facts are not in the notice"));
        assertFalse(IssueMatchingAgent.isPartialOnClientFact("partial", "the client has not paid"), "the token is upper-case CLIENT");
        assertFalse(IssueMatchingAgent.isPartialOnClientFact("full", "CLIENT facts copied to the list"));
        assertFalse(IssueMatchingAgent.isPartialOnClientFact("none", "CLIENT"));
        assertFalse(IssueMatchingAgent.isPartialOnClientFact("partial", null));

        // the record defaults: old constructors and a null list give an empty list
        IssueMatchingAgent.MatchedIssue v3 = new IssueMatchingAgent.MatchedIssue(1, "full", "demand", List.of("NDK-006"), List.of(), "w", List.of(), java.util.Map.of(), List.of(), List.of());
        assertTrue(v3.clientFactsToConfirm().isEmpty());
        IssueMatchingAgent.MatchedIssue nullList = new IssueMatchingAgent.MatchedIssue(1, "full", "demand", List.of("NDK-006"), List.of(), "w", List.of(), java.util.Map.of(), List.of(), List.of(), null);
        assertTrue(nullList.clientFactsToConfirm().isEmpty());
        IssueMatchingAgent.MatchedIssue v3_2 = new IssueMatchingAgent.MatchedIssue(1, "full", "demand", List.of("NDK-006"), List.of(), "w", List.of(), java.util.Map.of(), List.of(), List.of(), List.of("nil returns were filed"));
        assertEquals(List.of("nil returns were filed"), v3_2.clientFactsToConfirm());
    }

    // ---------------------------------------------------------------- v3.4: consequential kind, parent_issue_no, defect_checks

    private static final String TEN_CHECKS_ONE_DEFECT = """
            "defect_checks": {
              "din_rfn": {"result": "defect", "basis": "header: DIN field reads [____]"},
              "signature": {"result": "ok", "basis": "header: digitally signed"},
              "limitation": {"result": "ok", "basis": "SCN 02.05.2024 vs deadline 31.05.2024 FY 2019-20"},
              "premature_74": {"result": "ok", "basis": "not applicable: Section 73 SCN"},
              "drc01a_missing": {"result": "ok", "basis": "outside the window"},
              "hearing": {"result": "ok", "basis": "para 6 offers a hearing"},
              "reply_time": {"result": "ok", "basis": "para 5 gives 30 days"},
              "officer": {"result": "ok", "basis": "not applicable: designation not stated"},
              "recovery_without_demand": {"result": "ok", "basis": "not applicable: SCN"},
              "non_speaking": {"result": "Cannot Test ", "basis": "last page cut off"}
            }""";

    @Test
    void v3_4ConsequentialIssue_hasKindAndParent_andIsMatchedLikeADemandIssue() {
        String json = "{\"notice\": {}, " + TEN_CHECKS_ONE_DEFECT + ","
                + """
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "ITC mismatch", "paras": ["3", "4"]},
                             {"ground_no": 2, "kind": "consequential", "parent_issue_no": 1, "label": "Section 50(3) interest", "paras": ["4"]},
                             {"ground_no": 3, "kind": "defect", "label": "DIN missing", "paras": ["header"]}],
                 "issues": [
                   {"issue_no": 1, "kind": "demand", "status": "full", "card_id": "NDK-006", "alternates": [], "why": "a; interest: ground 2", "notice_paras": ["3", "4"]},
                   {"issue_no": 2, "kind": " Consequential ", "parent_issue_no": 1, "status": "full", "card_id": "NDK-050", "alternates": ["NDK-051"],
                    "why": "Consequential to ground 1", "client_facts_to_confirm": ["ITC was reversed before utilisation"], "notice_paras": ["4"]},
                   {"issue_no": 3, "kind": "defect", "status": "full", "card_id": "NDK-141", "alternates": [], "why": "DIN missing", "notice_paras": ["header"]}],
                 "para_map": {"3": [1], "4": [1, 2]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(json, "NDK-006", "NDK-050", "NDK-051", "NDK-141"));

        assertTrue(result.flags().isEmpty(), result.flags().toString());
        assertEquals(3, result.issues().size());

        IssueMatchingAgent.MatchedIssue demand = result.issues().get(0);
        assertEquals(IssueMatchingAgent.KIND_DEMAND, demand.kind());
        assertNull(demand.parentIssueNo(), "a demand issue has no parent");
        assertFalse(demand.isConsequential());

        IssueMatchingAgent.MatchedIssue consequential = result.issues().get(1);
        assertEquals(IssueMatchingAgent.KIND_CONSEQUENTIAL, consequential.kind(), "kind is trimmed and case-insensitive");
        assertEquals("consequential", IssueMatchingAgent.KIND_CONSEQUENTIAL);
        assertTrue(consequential.isConsequential());
        assertFalse(consequential.isDefect());
        assertEquals(1, consequential.parentIssueNo());
        assertEquals("full", consequential.status());
        assertEquals(List.of("NDK-050"), consequential.cardIds(), "matched like any other issue: primary only in cardIds");
        assertEquals(List.of("NDK-051"), consequential.alternateCardIds());
        assertEquals(List.of("ITC was reversed before utilisation"), consequential.clientFactsToConfirm());
        assertTrue(consequential.flags().isEmpty());

        IssueMatchingAgent.MatchedIssue defect = result.issues().get(2);
        assertTrue(defect.isDefect());
        assertNull(defect.parentIssueNo());
        assertEquals(List.of(1, 2), result.paraMap().get("4"), "the interest paragraph maps to the parent and the consequential issue");

        // the ten checks are read in order, result lower-cased and trimmed, basis kept
        assertEquals(IssueMatchingAgent.DEFECT_CHECK_NAMES, List.copyOf(result.defectChecks().keySet()));
        assertEquals(new IssueMatchingAgent.DefectCheck("defect", "header: DIN field reads [____]"), result.defectChecks().get("din_rfn"));
        assertTrue(result.defectChecks().get("din_rfn").isDefect());
        assertEquals("cannot test", result.defectChecks().get("non_speaking").result());
        assertFalse(result.defectChecks().get("non_speaking").isDefect());
        assertEquals(1, IssueMatchingAgent.defectResultCount(result.defectChecks()));
    }

    @Test
    void v3_4DefectChecksMissing_whenAbsentOrFewerThanTen() {
        // absent (v3.3 output): defect_checks_missing only; no count mismatch is tested against an absent object
        String absent = """
                {"notice": {},
                 "grounds": [{"ground_no": 1, "kind": "defect", "label": "DIN missing", "paras": ["header"]}],
                 "issues": [{"issue_no": 1, "kind": "defect", "status": "full", "card_id": "NDK-141", "alternates": [], "why": "w", "notice_paras": ["header"]}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult noChecks = run(agentReturning(absent, "NDK-141"));
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), noChecks.flags());
        assertEquals("defect_checks_missing", IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING);
        assertTrue(noChecks.defectChecks().isEmpty());
        assertEquals("full", noChecks.issues().get(0).status(), "informational: the issues are kept");

        // nine entries (one "defect", one defect ground): missing, but the counts agree so no mismatch
        String nine = """
                {"notice": {},
                 "defect_checks": {"din_rfn": {"result": "defect", "basis": "header"}, "signature": {"result": "ok"}, "limitation": {"result": "ok"},
                                   "premature_74": {"result": "ok"}, "drc01a_missing": {"result": "ok"}, "hearing": {"result": "ok"},
                                   "reply_time": {"result": "ok"}, "officer": {"result": "ok"}, "recovery_without_demand": {"result": "ok"}},
                 "grounds": [{"ground_no": 1, "kind": "defect", "label": "DIN missing", "paras": ["header"]}],
                 "issues": [{"issue_no": 1, "kind": "defect", "status": "none", "card_id": null, "alternates": [], "why": "w", "notice_paras": ["header"]}],
                 "para_map": {}}
                """;
        IssueMatchingAgent.MatchingResult nineChecks = run(agentReturning(nine, "NDK-141"));
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), nineChecks.flags());
        assertEquals(9, nineChecks.defectChecks().size());
        assertEquals("", nineChecks.defectChecks().get("signature").basis(), "a missing basis reads as empty");

        // not an object: read as absent
        String notObject = """
                {"notice": {}, "defect_checks": ["din_rfn"], "grounds": [], "issues": [], "para_map": {}}
                """;
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_CHECKS_MISSING), run(agentReturning(notObject)).flags());
    }

    @Test
    void v3_4DefectCountMismatch_whenDefectGroundsDifferFromDefectResults() {
        // ten checks, one "defect" result, but two defect grounds
        String tooManyGrounds = "{\"notice\": {}, " + TEN_CHECKS_ONE_DEFECT + ","
                + """
                 "grounds": [{"ground_no": 1, "kind": "defect", "label": "DIN missing", "paras": ["header"]},
                             {"ground_no": 2, "kind": "defect", "label": "no hearing", "paras": ["6"]}],
                 "issues": [{"issue_no": 1, "kind": "defect", "status": "full", "card_id": "NDK-141", "alternates": [], "why": "w", "notice_paras": ["header"]},
                            {"issue_no": 2, "kind": "defect", "status": "none", "card_id": null, "alternates": [], "why": "w", "notice_paras": ["6"]}],
                 "para_map": {"6": [2]}}
                """;
        IssueMatchingAgent.MatchingResult result = run(agentReturning(tooManyGrounds, "NDK-141"));
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_COUNT_MISMATCH), result.flags());
        assertEquals("defect_count_mismatch", IssueMatchingAgent.FLAG_DEFECT_COUNT_MISMATCH);
        assertEquals(2, result.issues().size(), "informational: both defect issues are kept");

        // ten checks, one "defect" result, no defect ground (the finding the checklist exists for): mismatch
        String noGround = "{\"notice\": {}, " + TEN_CHECKS_ONE_DEFECT + ","
                + """
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "x", "paras": ["1"]}],
                 "issues": [{"issue_no": 1, "kind": "demand", "status": "full", "card_id": "NDK-006", "alternates": [], "why": "w", "notice_paras": ["1"]}],
                 "para_map": {"1": [1]}}
                """;
        assertEquals(List.of(IssueMatchingAgent.FLAG_DEFECT_COUNT_MISMATCH), run(agentReturning(noGround, "NDK-006")).flags());

        // ten checks, no "defect" result, no defect ground, a consequential issue: no flag at all
        String allOk = """
                {"notice": {},
                 "defect_checks": {"din_rfn": "ok", "signature": "ok", "limitation": "ok", "premature_74": "ok", "drc01a_missing": "ok",
                                   "hearing": "ok", "reply_time": "ok", "officer": "ok", "recovery_without_demand": "ok", "non_speaking": "ok"},
                 "grounds": [{"ground_no": 1, "kind": "demand", "label": "x", "paras": ["1"]},
                             {"ground_no": 2, "kind": "consequential", "parent_issue_no": 1, "label": "penalty", "paras": ["1"]}],
                 "issues": [{"issue_no": 1, "kind": "demand", "status": "full", "card_id": "NDK-006", "alternates": [], "why": "w", "notice_paras": ["1"]},
                            {"issue_no": 2, "kind": "consequential", "parent_issue_no": "1", "status": "partial", "card_id": "NDK-050", "alternates": [],
                             "why": "DECISIVE \\"x\\": absent - y", "notice_paras": ["1"]}],
                 "para_map": {"1": [1, 2]}}
                """;
        IssueMatchingAgent.MatchingResult ok = run(agentReturning(allOk, "NDK-006", "NDK-050"));
        assertTrue(ok.flags().isEmpty(), ok.flags().toString());
        assertEquals(10, ok.defectChecks().size(), "a bare text value is read as the result");
        assertEquals(new IssueMatchingAgent.DefectCheck("ok", ""), ok.defectChecks().get("hearing"));
        assertEquals(1, ok.issues().get(1).parentIssueNo(), "a numeric string parent is read");
        assertTrue(ok.issues().get(1).isConsequential());
    }

    @Test
    void v3_4Readers_andRecordDefaults() throws Exception {
        assertEquals(3, IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"parent_issue_no\": 3}")));
        assertEquals(2, IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"parent_issue_no\": \" 2 \"}")));
        assertNull(IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"parent_issue_no\": null}")));
        assertNull(IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"parent_issue_no\": \"one\"}")));
        assertNull(IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"parent_issue_no\": 1.5}")));
        assertNull(IssueMatchingAgent.readParentIssueNo(objectMapper.readTree("{\"issue_no\": 1}")));

        var checks = IssueMatchingAgent.readDefectChecks(objectMapper.readTree(
                "{\"defect_checks\": {\" hearing \": {\"result\": \" DEFECT \", \"basis\": \" para 6 \"}, \"\": {\"result\": \"ok\"}, \"officer\": 7, \"din_rfn\": \"ok\"}}"));
        assertEquals(List.of("hearing", "din_rfn"), List.copyOf(checks.keySet()), "keys trimmed, blank keys and non-object/non-text values dropped, order kept");
        assertEquals(new IssueMatchingAgent.DefectCheck("defect", "para 6"), checks.get("hearing"));
        assertEquals(1, IssueMatchingAgent.defectResultCount(checks));
        assertTrue(IssueMatchingAgent.readDefectChecks(objectMapper.readTree("{\"defect_checks\": null}")).isEmpty());
        assertTrue(IssueMatchingAgent.readDefectChecks(objectMapper.readTree("{\"issues\": []}")).isEmpty());
        assertEquals(0, IssueMatchingAgent.defectResultCount(null));
        assertEquals(List.of("din_rfn", "signature", "limitation", "premature_74", "drc01a_missing",
                "hearing", "reply_time", "officer", "recovery_without_demand", "non_speaking"), IssueMatchingAgent.DEFECT_CHECK_NAMES);

        // record defaults: the older constructors have no parent and no checks
        IssueMatchingAgent.MatchedIssue v3_3 = new IssueMatchingAgent.MatchedIssue(1, "full", "demand", List.of("NDK-006"), List.of(), "w", List.of(), java.util.Map.of(), List.of(), List.of(), List.of("f"));
        assertNull(v3_3.parentIssueNo());
        IssueMatchingAgent.MatchedIssue v3_4 = new IssueMatchingAgent.MatchedIssue(2, "full", "consequential", List.of("NDK-050"), List.of(), "w", List.of(), java.util.Map.of(), List.of(), List.of(), List.of(), 1);
        assertEquals(1, v3_4.parentIssueNo());
        assertTrue(v3_4.isConsequential());
        IssueMatchingAgent.MatchingResult old = new IssueMatchingAgent.MatchingResult(null, List.of(), java.util.Map.of(), List.of(), List.of(), "h", 0, 0, 0, 0);
        assertTrue(old.defectChecks().isEmpty());
        IssueMatchingAgent.MatchingResult nullChecks = new IssueMatchingAgent.MatchingResult(null, List.of(), java.util.Map.of(), List.of(), List.of(), "h", 0, 0, 0, 0, null);
        assertTrue(nullChecks.defectChecks().isEmpty());
        assertEquals(new IssueMatchingAgent.DefectCheck("", ""), new IssueMatchingAgent.DefectCheck(null, null));
    }

    @Test
    void matchedIssue_kindIsNormalized_andOldConstructorsAreDemand() {
        assertEquals("demand", IssueMatchingAgent.normalizeKind(null));
        assertEquals("demand", IssueMatchingAgent.normalizeKind(""));
        assertEquals("demand", IssueMatchingAgent.normalizeKind("demand"));
        assertEquals("defect", IssueMatchingAgent.normalizeKind(" Defect "));
        assertEquals("consequential", IssueMatchingAgent.normalizeKind("CONSEQUENTIAL "));
        assertEquals("demand", IssueMatchingAgent.normalizeKind("procedural"));

        IssueMatchingAgent.MatchedIssue v1 = new IssueMatchingAgent.MatchedIssue(1, "full", List.of("NDK-006"), "w", List.of(), java.util.Map.of(), List.of(), List.of());
        assertEquals("demand", v1.kind());
        IssueMatchingAgent.MatchedIssue v2 = new IssueMatchingAgent.MatchedIssue(1, "full", List.of("NDK-006"), List.of("NDK-007"), "w", List.of(), java.util.Map.of(), List.of(), List.of());
        assertEquals("demand", v2.kind());
        IssueMatchingAgent.MatchedIssue nullKind = new IssueMatchingAgent.MatchedIssue(1, "none", null, List.of(), null, "w", List.of(), java.util.Map.of(), List.of(), List.of());
        assertEquals("demand", nullKind.kind());
        assertTrue(nullKind.alternateCardIds().isEmpty());
        IssueMatchingAgent.MatchedIssue defect = new IssueMatchingAgent.MatchedIssue(2, "full", "defect", List.of("NDK-141"), List.of(), "w", List.of(), java.util.Map.of(), List.of("header"), List.of());
        assertTrue(defect.isDefect());
        assertEquals("NDK-141", defect.primaryCardId());
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
                .thenReturn(failedResponse("max_tokens", LlmResponse.FAILURE_MAX_TOKENS, 12000))
                .thenReturn(new LlmResponse(VALID_JSON, "claude-sonnet-5-5", "anthropic", 1000, 3000, "end_turn", 0, 9000));
        ApiUsageLogService usage = Mockito.mock(ApiUsageLogService.class);

        IssueMatchingAgent.MatchingResult result = agentWith(mockLlm, usage)
                .matchNoticeWithOcrText("OCR text", "scn_73", UUID.randomUUID(), UUID.randomUUID());

        assertFalse(result.flags().contains("matching_failed"));
        assertEquals("FAKE-SCN-1", result.notice().noticeNumber());
        Mockito.verify(mockLlm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("claude-sonnet-5-5"),
                Mockito.eq(12000), Mockito.eq("low"), Mockito.eq(true));
        Mockito.verify(mockLlm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("claude-sonnet-5-5"),
                Mockito.eq(20000), Mockito.eq("low"), Mockito.eq(true));
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.any(), Mockito.eq("matching"), Mockito.eq(1), Mockito.any(), Mockito.any());
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.any(), Mockito.eq("matching"), Mockito.eq(2), Mockito.any(), Mockito.any());
        assertEquals(15000, result.outputTokens(), "output tokens of the failed attempt are counted too");
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
