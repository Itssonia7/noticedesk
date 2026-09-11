package com.noticedesk.api.service.rag;

import com.noticedesk.api.model.rag.*;
import com.noticedesk.api.service.embedding.EmbeddingService;
import com.noticedesk.api.service.embedding.StubEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RagStoreServiceTest {

    private EmbeddingService embeddingService;
    private StubEmbeddingProvider stubEmbeddingProvider;
    private RagStoreService ragStoreService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService();
        stubEmbeddingProvider = new StubEmbeddingProvider();
        ragStoreService = new RagStoreService(null, embeddingService, stubEmbeddingProvider);
        RagStoreService.clearInMemoryStores();
    }

    @Test
    void testChunkingAndTokenEstimation() {
        String sampleText = "Notice under Section 73 of the CGST Act 2017. " +
                "The taxpayer is called upon to show cause why Input Tax Credit amounting to INR 5,00,000 " +
                "availed as per GSTR-3B but not reflecting in GSTR-2A should not be demanded along with interest and penalty under Section 50 and Section 122.";

        List<String> chunks = embeddingService.chunkText(sampleText, 500, 50);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).contains("Section 73"));

        int tokenCount = embeddingService.estimateTokens(sampleText);
        assertTrue(tokenCount > 10);
    }

    @Test
    void testDeterministicStubEmbedding() {
        String text = "Input Tax Credit mismatch defense under CBIC Circular 183/15/2022-GST";
        List<Double> vector1 = stubEmbeddingProvider.embedQuery(text);
        List<Double> vector2 = stubEmbeddingProvider.embedQuery(text);

        assertNotNull(vector1);
        assertEquals(1536, vector1.size());
        assertEquals(vector1, vector2, "Deterministic embedding vectors must be equal for same input text");
    }

    @Test
    void testIndexAndSearchEvidenceDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID matterId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String noticeText = "Form GST DRC-01 Show Cause Notice for FY 2017-18 ITC mismatch under Section 73.";

        List<EvidenceChunk> created = ragStoreService.indexEvidenceDocument(
                tenantId, matterId, documentId, "DRC01_Notice.pdf", noticeText, "SHOW_CAUSE_NOTICE"
        );

        assertFalse(created.isEmpty());
        assertEquals(matterId, created.get(0).matterId());

        List<EvidenceChunk> searchResults = ragStoreService.searchEvidence(matterId, "GST DRC-01 Show Cause Notice", 5);
        assertFalse(searchResults.isEmpty());
        assertEquals("DRC01_Notice.pdf", searchResults.get(0).filename());
        assertNotNull(searchResults.get(0).similarityScore());
    }

    @Test
    void testIndexAndSearchLegalChunk() {
        LegalChunk legalChunk = ragStoreService.indexLegalChunk(
                "CGST Act 2017",
                "Section 16(2)",
                "Eligibility and conditions for taking input tax credit",
                "No registered person shall be entitled to the credit of any input tax in respect of any supply of goods or services..."
        );

        assertNotNull(legalChunk);
        assertEquals("Section 16(2)", legalChunk.sectionOrPara());

        List<LegalChunk> searchResults = ragStoreService.searchLegal("Input tax credit eligibility Section 16", 5);
        assertFalse(searchResults.isEmpty());
        assertEquals("Section 16(2)", searchResults.get(0).sectionOrPara());
    }

    @Test
    void testConfidenceAssessmentHighMatch() {
        UUID matterId = UUID.randomUUID();
        String text = "CBIC Circular 183/15/2022-GST procedure for verification of ITC mismatch between GSTR-3B and GSTR-2A";

        ragStoreService.indexLegalChunk(
                "CBIC Circular 183/15/2022-GST",
                "Para 4",
                "ITC Verification Procedure",
                text
        );

        RagContextBundle bundle = ragStoreService.getRagContext(matterId, text, 5, 5);
        ConfidenceAssessment assessment = ragStoreService.evaluateCascadeConfidence(bundle);

        assertNotNull(assessment);
        assertTrue(assessment.isHighConfidence(), "Score for exact text match must be >= 0.70 triggering high confidence");
        assertEquals("HIGH_MATCH", assessment.matchTier());
        assertNotNull(assessment.guidedTemplate());
        assertTrue(assessment.guidedTemplate().contains("GOLD-STANDARD TEMPLATE GUIDANCE"));
    }

    @Test
    void testConfidenceAssessmentNovelCase() {
        UUID matterId = UUID.randomUUID();
        RagContextBundle emptyBundle = ragStoreService.getRagContext(matterId, "Unrelated query", 5, 5);
        ConfidenceAssessment assessment = ragStoreService.evaluateCascadeConfidence(emptyBundle);

        assertNotNull(assessment);
        assertFalse(assessment.isHighConfidence());
        assertEquals("NOVEL_CASE", assessment.matchTier());
        assertNull(assessment.guidedTemplate());
    }
}
