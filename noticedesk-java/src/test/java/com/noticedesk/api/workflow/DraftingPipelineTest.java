package com.noticedesk.api.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.rag.LegalChunk;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.GstCorpusMatcherService;
import com.noticedesk.api.service.GstTemplateFillerService;
import com.noticedesk.api.service.embedding.EmbeddingService;
import com.noticedesk.api.service.embedding.StubEmbeddingProvider;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DraftingPipelineTest {

    private RagStoreService ragStoreService;

    @BeforeEach
    void setUp() {
        EmbeddingService embeddingService = new EmbeddingService();
        StubEmbeddingProvider stubEmbeddingProvider = new StubEmbeddingProvider();
        ragStoreService = new RagStoreService(null, embeddingService, stubEmbeddingProvider);
        RagStoreService.clearInMemoryStores();
    }

    @Test
    void testPipelineDeduplicationWhenTwoIssuesMatchSameChunk() {
        // Index a single legal chunk in RAG
        LegalChunk sharedChunk = ragStoreService.indexLegalChunk(
                "CBIC Circular 183", "Para 3", "ITC Verification",
                "Shared Legal Argument for Section 16(2) ITC Mismatch."
        );

        // Search twice for two different queries
        List<LegalChunk> search1 = ragStoreService.searchLegal("ITC Mismatch Issue 1", 1);
        List<LegalChunk> search2 = ragStoreService.searchLegal("ITC Mismatch Issue 2", 1);

        assertFalse(search1.isEmpty());
        assertFalse(search2.isEmpty());
        assertEquals(search1.get(0).content(), search2.get(0).content());

        // Deduplication set
        Set<String> uniqueChunkContents = new LinkedHashSet<>();
        uniqueChunkContents.add(search1.get(0).content());
        uniqueChunkContents.add(search2.get(0).content());

        // Assert set size is 1 (deduplicated)
        assertEquals(1, uniqueChunkContents.size());
        assertTrue(uniqueChunkContents.contains("Shared Legal Argument for Section 16(2) ITC Mismatch."));
    }

    @Test
    void testMultiIssueNoticeScenarios() {
        // Index 2 chunks in RAG with matching titles/descriptions for SHA-256 stub vector match
        ragStoreService.indexLegalChunk("Act 1", "Sec 16(2)", "ITC Mismatch", "ITC Mismatch GSTR 2A vs 3B");
        ragStoreService.indexLegalChunk("Act 2", "Sec 74", "Section 16(4) Bar", "Section 16(4) Bar Time limitation");

        LlmFactory llmFactory = Mockito.mock(LlmFactory.class);
        LlmProvider extractorProvider = Mockito.mock(LlmProvider.class);
        LlmProvider opusProvider = Mockito.mock(LlmProvider.class);
        LlmProvider formatterProvider = Mockito.mock(LlmProvider.class);

        Mockito.when(llmFactory.getLlmForAgent("extraction")).thenReturn(extractorProvider);
        Mockito.when(llmFactory.getOpusProvider()).thenReturn(opusProvider);
        Mockito.when(llmFactory.getLlmForAgent("formatting")).thenReturn(formatterProvider);

        // Mock Extraction returning 3 issues: 2 matched, 1 unmatched
        String extractionJson = """
                {
                  "notice_info": { "notice_number": "SCN-301" },
                  "issues": [
                    { "issue_id": "I1", "title": "ITC Mismatch", "description": "GSTR 2A vs 3B", "statutory_section": "Sec 16(2)" },
                    { "issue_id": "I2", "title": "Section 16(4) Bar", "description": "Time limitation", "statutory_section": "Sec 74" },
                    { "issue_id": "I3", "title": "Novel Crypto Tax", "description": "Unknown Crypto Tax", "statutory_section": "Sec 99" }
                  ]
                }
                """;

        Mockito.when(extractorProvider.generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
                .thenReturn(new LlmResponse(extractionJson, "haiku", "anthropic", 10, 10));

        Mockito.when(opusProvider.generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
                .thenReturn(new LlmResponse("Opus Template Rebuttal for Novel Crypto Tax", "opus", "anthropic", 20, 20));

        String fifteenSectionFormatJson = """
                {
                  "sections": [
                    { "num": 1, "title": "Addressee and Reference Block", "body_html": "<p>Block</p>" },
                    { "num": 2, "title": "Subject", "body_html": "<p>Sub</p>" },
                    { "num": 3, "title": "Synopsis", "body_html": "<p>Syn</p>" },
                    { "num": 4, "title": "Statement of Facts", "body_html": "<p>Facts</p>" },
                    { "num": 5, "title": "Preliminary Objections — Jurisdiction", "body_html": "<p>Juris</p>" },
                    { "num": 6, "title": "Preliminary Objections — Limitation and Procedure", "body_html": "<p>Lim</p>" },
                    { "num": 7, "title": "Para-wise Reply", "body_html": "<p>Para</p>" },
                    { "num": 8, "title": "Ground 1", "body_html": "<p>G1</p>" },
                    { "num": 9, "title": "Ground 2", "body_html": "<p>G2</p>" },
                    { "num": 10, "title": "Ground 3", "body_html": "<p>G3</p>" },
                    { "num": 11, "title": "Without Prejudice — Alternative Submissions", "body_html": "<p>Alt</p>" },
                    { "num": 12, "title": "Quantum, Interest and Computation", "body_html": "<p>Quant</p>" },
                    { "num": 13, "title": "Prayer", "body_html": "<p>Prayer</p>" },
                    { "num": 14, "title": "Annexures", "body_html": "<p>Annex</p>" },
                    { "num": 15, "title": "Declaration and Signature Block", "body_html": "<p>Sign</p>" }
                  ],
                  "internal_partner_note": "Note",
                  "client_summary": "Summary"
                }
                """;

        Mockito.when(formatterProvider.generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
                .thenReturn(new LlmResponse(fifteenSectionFormatJson, "haiku", "anthropic", 30, 30));

        DraftingAgent agent = new DraftingAgent(llmFactory, new ObjectMapper(), ragStoreService, null);
        DraftingAgent.DraftingInput input = createSampleInput();

        // 1. Issue extraction
        DraftingAgent.ExtractionResult extractionResult = agent.extractIssues(input);
        assertEquals(3, extractionResult.issues().size());

        // 2. Per-issue RAG & Opus dispatching
        Set<String> uniqueChunkContents = new LinkedHashSet<>();
        List<DraftingAgent.ExtractedIssue> unmatched = new ArrayList<>();

        for (DraftingAgent.ExtractedIssue issue : extractionResult.issues()) {
            List<LegalChunk> hits = ragStoreService.searchLegal(issue.title() + " " + issue.description(), 1);
            if (!hits.isEmpty() && hits.get(0).similarityScore() != null && hits.get(0).similarityScore() >= 0.70) {
                uniqueChunkContents.add(hits.get(0).content());
            } else {
                unmatched.add(issue);
            }
        }

        // 2/3 matched, 1/3 unmatched
        assertEquals(1, unmatched.size());
        assertEquals("Novel Crypto Tax", unmatched.get(0).title());

        // 3. Call Opus for unmatched issue
        for (DraftingAgent.ExtractedIssue un : unmatched) {
            String newTemplate = agent.generateOpusTemplateForUnmatchedIssue(un, input);
            ragStoreService.saveNewChunk(un.title(), newTemplate);
            uniqueChunkContents.add(newTemplate);
        }

        Mockito.verify(opusProvider, Mockito.times(1)).generateText(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble());

        // 4. Merge & format 15 sections
        DraftingAgent.GeneratedDraft draft = agent.mergeAndFormatDraft(input, new ArrayList<>(uniqueChunkContents));
        assertNotNull(draft);
        assertEquals(15, draft.sections().size());
        assertEquals("Addressee and Reference Block", draft.sections().get(0).title());
        assertEquals("Declaration and Signature Block", draft.sections().get(14).title());
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
