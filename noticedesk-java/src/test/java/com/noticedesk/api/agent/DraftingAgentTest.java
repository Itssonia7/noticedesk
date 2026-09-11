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
        draftingAgent = new DraftingAgent(llmFactory, objectMapper, ragStoreService);
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
}
