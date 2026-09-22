package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.embedding.EmbeddingProvider;
import com.noticedesk.api.service.embedding.EmbeddingService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.rag.CorpusRagSeederService;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenariosTestingRunnerTest {

    private AppProperties properties;
    private ObjectMapper objectMapper;
    private GstCorpusMatcherService corpusMatcherService;
    private GstTemplateFillerService templateFillerService;
    private RagStoreService ragStoreService;
    private CorpusRagSeederService seederService;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        objectMapper = new ObjectMapper();
        corpusMatcherService = new GstCorpusMatcherService(properties, objectMapper);
        corpusMatcherService.init();

        templateFillerService = new GstTemplateFillerService();

        EmbeddingService embeddingService = new EmbeddingService();
        EmbeddingProvider embeddingProvider = new EmbeddingProvider() {
            @Override
            public List<List<Double>> embedDocuments(List<String> texts) {
                List<List<Double>> res = new ArrayList<>();
                for (String t : texts) {
                    res.add(List.of(0.1, 0.2, 0.3, 0.4, 0.5));
                }
                return res;
            }

            @Override
            public List<Double> embedQuery(String query) {
                return List.of(0.1, 0.2, 0.3, 0.4, 0.5);
            }
        };

        ragStoreService = new RagStoreService(null, embeddingService, embeddingProvider);
        seederService = new CorpusRagSeederService(properties, objectMapper, ragStoreService);
        seederService.seedCorpusToRag();
    }

    @Test
    void testScenario1_ExactMatchFastTrack() {
        System.out.println("=== RUNNING SCENARIO 1: EXACT MATCH (FAST-TRACK) ===");
        DraftingInput input = createSampleInput("Acme Traders Pvt Ltd", "27AAACA1234A1Z5", "scn_73", "SCN/2026/001");
        
        Optional<GstCorpusMatcherService.CorpusMatchResult> matchOpt =
                corpusMatcherService.matchNotice(input.notice(), "Reply_04D_Noticedesk_ITC_Mismatch_GSTR2A_3B_FY1718_1819");

        assertTrue(matchOpt.isPresent());
        GstCorpusMatcherService.CorpusMatchResult match = matchOpt.get();

        // Exact match score threshold >= 0.90
        assertEquals(GstCorpusMatcherService.Strategy.FAST_TRACK, match.strategy());
        assertTrue(match.matchScore() >= 0.90, "Score must be >= 0.90 for exact match");

        GeneratedDraft draft = templateFillerService.fillTemplate(input, match.draftPath(), match.noticeKind());
        assertNotNull(draft);
        assertEquals(15, draft.sections().size());

        exportTestSummary("output/Scenario_1_Exact_Match_Output.txt", "SCENARIO 1: EXACT MATCH (FAST-TRACK)", draft);
    }

    @Test
    void testScenario2_NovelNoticeAndAutoCaching() {
        System.out.println("=== RUNNING SCENARIO 2: NOVEL NOTICE (<70% SCORE) & AUTO-CACHING ===");
        
        Map<String, Object> noticeData = Map.of(
                "document_type", "unknown_custom_notice",
                "notice_number", "CUSTOM/999/2026",
                "issue", "Unprecedented GST Cryptographic Mining Levy Demand"
        );
        String ocr = "Rare novel GST demand on decentralized node operations without statutory precedent";

        Optional<GstCorpusMatcherService.CorpusMatchResult> matchOpt =
                corpusMatcherService.matchNotice(noticeData, ocr);

        assertTrue(matchOpt.isPresent());
        GstCorpusMatcherService.CorpusMatchResult match = matchOpt.get();
        assertEquals(GstCorpusMatcherService.Strategy.NOVEL_LLM, match.strategy());
        assertTrue(match.matchScore() < 0.70, "Score must be < 0.70 for novel notice");

        // Simulate fresh draft & Auto-Caching
        DraftingInput input = createSampleInput("CryptoNode Systems India", "27BBBCC9876K1Z9", "custom_notice", "CUSTOM/999/2026");
        GeneratedDraft freshDraft = templateFillerService.fillTemplate(input, Paths.get("dummy.docx"), "novel_case");
        
        ragStoreService.cacheNovelDraft("Unprecedented Crypto Levy", "Reply for CryptoNode Systems", freshDraft.sections().get(6).bodyHtml());

        exportTestSummary("output/Scenario_2_Novel_Notice_Output.txt", "SCENARIO 2: NOVEL NOTICE & AUTO-CACHING", freshDraft);
    }

    @Test
    void testScenario3_HybridMultiIssue() {
        System.out.println("=== RUNNING SCENARIO 3: HYBRID MULTI-ISSUE (70%-89% SCORE) ===");
        
        Map<String, Object> noticeData = Map.of(
                "document_type", "detention_notice",
                "notice_number", "DET/2026/404",
                "issue", "Multi-issue Detention Section 129 + Eway bill breakdown"
        );
        String ocr = "Notice under Section 129 for detention of goods vehicle breakdown and missing eway part B";

        Optional<GstCorpusMatcherService.CorpusMatchResult> matchOpt =
                corpusMatcherService.matchNotice(noticeData, ocr);

        assertTrue(matchOpt.isPresent());
        GstCorpusMatcherService.CorpusMatchResult match = matchOpt.get();

        DraftingInput input = createSampleInput("Logistics India Ltd", "27LLLLM4321P1Z2", "detention_notice", "DET/2026/404");
        GeneratedDraft hybridDraft = templateFillerService.fillTemplate(input, match.draftPath(), match.noticeKind());

        exportTestSummary("output/Scenario_3_Hybrid_MultiIssue_Output.txt", "SCENARIO 3: HYBRID MULTI-ISSUE", hybridDraft);
    }

    private DraftingInput createSampleInput(String clientName, String gstin, String docType, String noticeNum) {
        return new DraftingInput(
                UUID.randomUUID(), "formal", "None", clientName, "AAACA1234A",
                "Private Limited Company", "GST", gstin, "Maharashtra", "GST",
                "2023-24", "2024-25",
                Map.of("document_type", docType, "notice_number", noticeNum, "din_or_rfn", "DIN-2026-99", "authority", "Proper Officer, Ward 10"),
                Map.of(), "OCR excerpt text", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );
    }

    private void exportTestSummary(String filename, String scenarioTitle, GeneratedDraft draft) {
        try {
            File out = new File("../" + filename);
            try (FileWriter writer = new FileWriter(out)) {
                writer.write("=================================================================\n");
                writer.write(scenarioTitle + "\n");
                writer.write("=================================================================\n");
                writer.write("Prompt Version: " + draft.promptVersion() + "\n");
                writer.write("Provider: " + draft.providerName() + "\n\n");
                for (DraftSection s : draft.sections()) {
                    writer.write("SECTION " + s.num() + ": " + s.title() + "\n");
                    writer.write(s.bodyHtml() + "\n\n");
                }
            }
            System.out.println("Test summary exported to " + out.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
