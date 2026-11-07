package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.model.rag.ConfidenceAssessment;
import com.noticedesk.api.model.rag.RagContextBundle;
import com.noticedesk.api.service.embedding.EmbeddingService;
import com.noticedesk.api.service.embedding.StubEmbeddingProvider;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DraftingAgentTest {

    private DraftingAgent draftingAgent;
    private RagStoreService ragStoreService;

    @BeforeEach
    void setUp() {
        EmbeddingService embeddingService = new EmbeddingService();
        StubEmbeddingProvider stubEmbeddingProvider = new StubEmbeddingProvider();
        ragStoreService = new RagStoreService(null, embeddingService, stubEmbeddingProvider);
        RagStoreService.clearInMemoryStores();

        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        ObjectMapper objectMapper = new ObjectMapper();
        draftingAgent = new DraftingAgent(llmFactory, objectMapper, ragStoreService, org.mockito.Mockito.mock(com.noticedesk.api.service.llm.ApiUsageLogService.class));
    }

    @Test
    void testDraftingInputConstructionWithRagContext() {
        UUID matterId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String legalText = "Clarification on ITC mismatch between GSTR-3B and GSTR-2A for FY 2017-18 under CBIC Circular 183/15/2022-GST.";

        // Index legal chunk and evidence document
        ragStoreService.indexLegalChunk(
                "CBIC Circular 183/15/2022-GST",
                "Para 3",
                "ITC Verification",
                legalText
        );

        ragStoreService.indexEvidenceDocument(
                tenantId, matterId, UUID.randomUUID(), "Supplier_Declaration.pdf",
                "CA Certificate confirming supplier tax payment for invoice 104.", "DECLARATION"
        );

        RagContextBundle ragContext = ragStoreService.getRagContext(matterId, legalText, 5, 5);
        ConfidenceAssessment confidence = ragStoreService.evaluateCascadeConfidence(ragContext);

        DraftingAgent.DraftingInput input = new DraftingAgent.DraftingInput(
                matterId, "firm", "Include section 16(2) defense",
                "Acme Traders Pvt Ltd", "ABCDE1234F", "PRIVATE_LIMITED",
                "GST", "27ABCDE1234F1Z5", "Maharashtra",
                "GST", "2017-18", null,
                Map.of("notice_number", "DRC-01/2023", "demand_amount", 500000.0),
                Map.of("issue", legalText),
                "Show Cause Notice OCR Text Excerpt",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                ragContext,
                confidence
        );

        assertNotNull(input.ragContext());
        assertFalse(input.ragContext().legalChunks().isEmpty());
        assertFalse(input.ragContext().evidenceChunks().isEmpty());
        assertNotNull(input.confidenceAssessment());
        assertTrue(input.confidenceAssessment().isHighConfidence());
        assertTrue(input.confidenceAssessment().guidedTemplate().contains("GOLD-STANDARD TEMPLATE GUIDANCE"));
    }

    @Test
    void testExtractionFailure_RetriesOnceAndThrowsException() {
        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        com.noticedesk.api.service.llm.LlmProvider mockProvider = Mockito.mock(com.noticedesk.api.service.llm.LlmProvider.class);
        Mockito.when(llmFactory.getLlmForAgent("extraction")).thenReturn(mockProvider);
        Mockito.when(mockProvider.generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
                .thenReturn(new com.noticedesk.api.service.llm.LlmResponse("Invalid non-JSON text response", "haiku", "anthropic", 10, 10));

        DraftingAgent agent = new DraftingAgent(llmFactory, new ObjectMapper(), ragStoreService, null);
        DraftingAgent.DraftingInput input = createSampleInput();

        assertThrows(com.noticedesk.api.service.llm.JsonSchemaValidationException.class, () -> agent.extractIssues(input));
        Mockito.verify(mockProvider, Mockito.times(2)).generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble());
    }

    @Test
    void testExtractionSuccess() {
        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        com.noticedesk.api.service.llm.LlmProvider mockProvider = Mockito.mock(com.noticedesk.api.service.llm.LlmProvider.class);
        Mockito.when(llmFactory.getLlmForAgent("extraction")).thenReturn(mockProvider);
        String validJson = """
                {
                  "notice_info": { "notice_number": "SCN-101", "total_demand_amount": 100000.0 },
                  "issues": [
                    { "issue_id": "ISSUE-1", "title": "ITC Mismatch", "description": "GSTR 2A vs 3B", "statutory_section": "Section 16(2)" },
                    { "issue_id": "ISSUE-2", "title": "Section 16(4) Bar", "description": "Time limitation", "statutory_section": "Section 16(4)" }
                  ]
                }
                """;
        Mockito.when(mockProvider.generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
                .thenReturn(new com.noticedesk.api.service.llm.LlmResponse(validJson, "haiku", "anthropic", 10, 10));

        DraftingAgent agent = new DraftingAgent(llmFactory, new ObjectMapper(), ragStoreService, null);
        DraftingAgent.ExtractionResult result = agent.extractIssues(createSampleInput());

        assertNotNull(result);
        assertEquals(2, result.issues().size());
        assertEquals("ITC Mismatch", result.issues().get(0).title());
    }

    private DraftingAgent.DraftingInput createSampleInput() {
        return new DraftingAgent.DraftingInput(
                UUID.randomUUID(), "firm", "",
                "Acme Traders Pvt Ltd", "ABCDE1234F", "PRIVATE_LIMITED",
                "GST", "27ABCDE1234F1Z5", "Maharashtra",
                "GST", "2017-18", null,
                Map.of("notice_number", "DRC-01/2023", "demand_amount", 500000.0),
                Map.of(),
                "Show Cause Notice OCR Text Excerpt",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                null, null
        );
    }
}
