package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent.CitationVerificationResult;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Stage3AgentsTest {

    private AppProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new AppProperties();

        AppProperties.Llm llm = new AppProperties.Llm();
        llm.setModelPartialDrafting("test-model-partial");
        llm.setModelNewIssue("test-model-new-issue");
        llm.setModelHighStakes("test-model-high-stakes");
        llm.setProviderPrimary("stub");
        properties.setLlm(llm);

        AppProperties.Drafting drafting = new AppProperties.Drafting();
        AppProperties.Drafting.HighStakes hs = new AppProperties.Drafting.HighStakes();
        hs.setSection74(true);
        hs.setAppealStage(true);
        hs.setDemandThreshold("1000000.0");
        drafting.setHighStakes(hs);
        properties.setDrafting(drafting);

        AppProperties.Citation citation = new AppProperties.Citation();
        citation.setProvider("stub");
        citation.setIndiankanoonApiToken(null);
        citation.setMaxCallsPerDraft(15);
        properties.setCitation(citation);
    }

    @Test
    void testPartialDraftingAgentStubExecution() {
        PartialDraftingAgent agent = new PartialDraftingAgent(properties, objectMapper, null, null);
        // v2 result shape: cardIds holds only the primary, the matcher's other candidates are alternates
        MatchedIssue issue = new MatchedIssue(1, "partial", List.of("CARD-006"), List.of("CARD-007", "CARD-008"),
                "Why", List.of(), Map.of(), List.of(), List.of());
        assertEquals("CARD-006", issue.primaryCardId());
        assertEquals(List.of("CARD-007", "CARD-008"), issue.alternateCardIds());

        PartialDraftingResult result = agent.generatePartialDrafting(issue, null, null, "scn_73", UUID.randomUUID());

        assertNotNull(result);
        assertFalse(result.sections().isEmpty());
        assertTrue(result.sections().get(0).html().contains("Additional argument"));
    }

    @Test
    void matchedIssue_v1Constructor_hasNoAlternates_andNoPrimaryWhenNone() {
        MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "Why", List.of(), Map.of(), List.of(), List.of());
        assertEquals(List.of(), issue.alternateCardIds());
        assertNull(issue.primaryCardId());
        MatchedIssue nullAlternates = new MatchedIssue(1, "full", List.of("CARD-006"), null, "Why", List.of(), Map.of(), List.of(), List.of());
        assertEquals(List.of(), nullAlternates.alternateCardIds(), "null alternates read as empty");
    }

    @Test
    void testNewIssueDraftingAgentStubExecution() {
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(properties, objectMapper, null, null);
        MatchedIssue issue = new MatchedIssue(2, "none", List.of(), "Why", List.of(), Map.of(), List.of(), List.of());

        NewIssueDraftingResult result = agent.generateNewIssueDraft(issue, null, "OCR Text", "scn_73", UUID.randomUUID());

        assertNotNull(result);
        assertEquals(2, result.issueNo());
        assertTrue(result.section04Html().contains("Original grounds of defence"));
        assertTrue(result.strengthNote().contains("Strength Note"));
    }

    @Test
    void testHighStakesDraftingAgentTriggers() {
        HighStakesDraftingAgent agent = new HighStakesDraftingAgent(properties, objectMapper, null, null);

        assertTrue(agent.isHighStakesActive("Section 74", "scn_74", 50000.0));
        assertTrue(agent.isHighStakesActive("Section 73", "appeal_stage", 50000.0));
        assertTrue(agent.isHighStakesActive("Section 73", "scn_73", 1500000.0));
        assertFalse(agent.isHighStakesActive("Section 73", "scn_73", 50000.0));
    }

    @Test
    void testHighStakesDraftingAgentOffByDefault() {
        // Fresh properties: no HIGH_STAKES_SECTION_74 / HIGH_STAKES_APPEAL_STAGE / HIGH_STAKES_DEMAND_THRESHOLD.
        AppProperties defaults = new AppProperties();
        AppProperties.Drafting.HighStakes hs = defaults.getDrafting().getHighStakes();
        assertFalse(hs.getSection74());
        assertFalse(hs.getAppealStage());
        assertTrue(hs.getDemandThreshold() == null || hs.getDemandThreshold().isBlank());

        HighStakesDraftingAgent agent = new HighStakesDraftingAgent(defaults, objectMapper, null, null);

        assertFalse(agent.isHighStakesActive("Section 74", "scn_74", 50000.0));
        assertFalse(agent.isHighStakesActive("Section 73", "appeal_stage", 50000.0));
    }

    @Test
    void testCitationVerificationAgentMissingTokenDoesNotCrash() {
        AppProperties.Citation citConfig = properties.getCitation();
        citConfig.setProvider("indiankanoon");
        citConfig.setIndiankanoonApiToken(""); // Missing token

        CitationVerificationAgent agent = new CitationVerificationAgent(objectMapper, properties, null, null);
        RawAiCitation raw = new RawAiCitation("State of West Bengal v. Kesoram Industries", "Supreme Court", 2004, null, "Taxing power");

        CitationVerificationResult result = agent.verifyStructuredCitations(List.of(raw), "<p>State of West Bengal v. Kesoram Industries</p>", "indiankanoon", UUID.randomUUID());

        assertNotNull(result);
        assertTrue(result.flags().contains("MISSING_INDIANKANOON_API_TOKEN"));
        assertEquals(1, result.citations().size());
        assertEquals("NOT_CHECKED", result.citations().get(0).status());
    }
}
