package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DocumentParsingAgent;
import com.noticedesk.api.agent.DocumentParsingAgent.ParseInput;
import com.noticedesk.api.agent.DocumentParsingAgent.ParsedDocument;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.embedding.EmbeddingProvider;
import com.noticedesk.api.service.embedding.EmbeddingService;
import com.noticedesk.api.service.embedding.EmbeddingFactory;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.rag.CorpusRagSeederService;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ClaudeTestingPdfPipelineRunnerTest {

    private AppProperties properties;
    private ObjectMapper objectMapper;
    private LlmFactory llmFactory;
    private DocumentParsingAgent parsingAgent;
    private GstCorpusMatcherService corpusMatcherService;
    private GstTemplateFillerService templateFillerService;
    private RagStoreService ragStoreService;
    private DraftingAgent draftingAgent;
    private DraftingPipelineService draftingPipelineService;
    private CitationVerificationAgent citationVerificationAgent;
    private DocxExportService docxExportService;
    private String resolvedApiKey;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AppProperties();
        objectMapper = new ObjectMapper();

        // 1. Resolve Anthropic API Key & Workspace ID & Gemini API Key
        resolvedApiKey = System.getenv("ANTHROPIC_API_KEY");
        String resolvedWorkspaceId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        String resolvedGeminiKey = System.getenv("GEMINI_API_KEY");
        if (resolvedApiKey == null || resolvedApiKey.isBlank() || resolvedWorkspaceId == null || resolvedWorkspaceId.isBlank()) {
            Path envPath = Paths.get("../apps/api/.env");
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    if ((resolvedApiKey == null || resolvedApiKey.isBlank()) && line.startsWith("ANTHROPIC_API_KEY=")) {
                        resolvedApiKey = line.substring("ANTHROPIC_API_KEY=".length()).trim();
                    } else if ((resolvedWorkspaceId == null || resolvedWorkspaceId.isBlank()) && line.startsWith("ANTHROPIC_WORKSPACE_ID=")) {
                        resolvedWorkspaceId = line.substring("ANTHROPIC_WORKSPACE_ID=".length()).trim();
                    }
                }
            }
        }
        if (resolvedGeminiKey == null || resolvedGeminiKey.isBlank()) {
            Path localEnv = Paths.get(".env");
            if (Files.exists(localEnv)) {
                List<String> lines = Files.readAllLines(localEnv);
                for (String line : lines) {
                    if (line.startsWith("GEMINI_API_KEY=")) {
                        resolvedGeminiKey = line.substring("GEMINI_API_KEY=".length()).trim();
                    }
                }
            }
        }

        boolean enableE2e = "true".equalsIgnoreCase(System.getProperty("enable.e2e.tests"))
                || System.getenv("ENABLE_E2E_TESTS") != null;

        if (enableE2e && resolvedApiKey != null && !resolvedApiKey.isBlank()) {
            properties.getLlm().setProviderPrimary("anthropic");
            properties.getLlm().getAnthropic().setApiKey(resolvedApiKey);
            if (resolvedWorkspaceId != null && !resolvedWorkspaceId.isBlank()) {
                properties.getLlm().getAnthropic().setWorkspaceId(resolvedWorkspaceId);
            }
            if (resolvedGeminiKey != null && !resolvedGeminiKey.isBlank()) {
                properties.getEmbedding().setProvider("gemini");
                properties.getLlm().getGemini().setApiKey(resolvedGeminiKey);
            }
            properties.getLlm().getAnthropic().setModel("claude-opus-4-7");
            properties.getLlm().setModelDrafting("claude-opus-4-7");
            properties.getLlm().setModelParsing("claude-haiku-4-5-20251001");
            properties.getLlm().setModelTriage("claude-opus-4-7");
            properties.getLlm().getAnthropic().setTimeoutSeconds(300.0);
            System.out.println("Loaded Anthropic API Key & Workspace ID for Java pipeline execution.");
        } else {
            properties.getLlm().setProviderPrimary("stub");
            System.out.println("Using stub LLM provider for standard automated unit test suite.");
        }

        llmFactory = new LlmFactory(properties);
        IdentityService identityService = new IdentityService(null);
        parsingAgent = new DocumentParsingAgent(llmFactory, identityService, properties, objectMapper);

        corpusMatcherService = new GstCorpusMatcherService(properties, objectMapper);
        corpusMatcherService.init();

        templateFillerService = new GstTemplateFillerService();

        EmbeddingService embeddingService = new EmbeddingService();
        EmbeddingProvider embeddingProvider = new EmbeddingFactory(properties).embeddingProvider();

        if (enableE2e) {
            try {
                org.springframework.jdbc.datasource.DriverManagerDataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource();
                dataSource.setDriverClassName("org.postgresql.Driver");
                dataSource.setUrl("jdbc:postgresql://127.0.0.1:5432/noticedesk_dev");
                dataSource.setUsername("noticedesk_app");
                dataSource.setPassword("noticedesk_app");
                org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);

                Integer count = jdbc.queryForObject("SELECT count(*) FROM legal_chunks", new java.util.HashMap<>(), Integer.class);
                System.out.println("=== RAG PRE-FLIGHT CHECK ===");
                System.out.println("Database URL: jdbc:postgresql://127.0.0.1:5432/noticedesk_dev");
                System.out.println("RagStoreService backed by real JDBC Template: " + (jdbc != null));
                System.out.println("legal_chunks Table Row Count: " + count);
                System.out.println("=============================");

                ragStoreService = new RagStoreService(jdbc, embeddingService, embeddingProvider);
            } catch (Exception e) {
                System.out.println("PostgreSQL not reachable for E2E runner: " + e.getMessage());
                ragStoreService = new RagStoreService(null, embeddingService, embeddingProvider);
            }
        } else {
            ragStoreService = new RagStoreService(null, embeddingService, embeddingProvider);
        }

        draftingAgent = new DraftingAgent(llmFactory, objectMapper, ragStoreService, org.mockito.Mockito.mock(com.noticedesk.api.service.llm.ApiUsageLogService.class));
        citationVerificationAgent = new CitationVerificationAgent(objectMapper);
        draftingPipelineService = new DraftingPipelineService(draftingAgent, ragStoreService, citationVerificationAgent, properties, objectMapper);
        docxExportService = new DocxExportService();
    }

    @Test
    void runPipelineForClaudeTestingPdf() throws Exception {
        boolean enableE2e = "true".equalsIgnoreCase(System.getProperty("enable.e2e.tests"))
                || System.getenv("ENABLE_E2E_TESTS") != null;
        if (!enableE2e) {
            System.out.println("Skipping E2E PDF Pipeline Runner test (ENABLE_E2E_TESTS not set)");
            return;
        }
        String pdfPathStr = "../Meridian_Fabtech_SCN_Hybrid_Test.pdf";
        File pdfFile = new File(pdfPathStr);
        if (!pdfFile.exists()) {
            pdfPathStr = "../claude testing /Sunrise_Polymers_SCN_Multi_Issue_Notice.pdf";
            pdfFile = new File(pdfPathStr);
        }

        System.out.println("=== RUNNING JAVA PIPELINE ON: " + pdfFile.getCanonicalPath() + " ===");

        // Step 1: OCR / Text Extraction
        long ocrStartTime = System.currentTimeMillis();
        String ocrText = extractPdfText(pdfFile.getAbsolutePath());
        long ocrDuration = System.currentTimeMillis() - ocrStartTime;
        System.out.println("1. OCR Extraction Completed (" + ocrDuration + " ms, " + ocrText.length() + " chars)");

        // Step 2: Document Parsing Agent (LLM Call 1)
        long parseStartTime = System.currentTimeMillis();
        ParseInput parseInput = new ParseInput(
                UUID.randomUUID().toString(),
                pdfFile.getName(),
                "web_upload",
                ocrText,
                "pdftotext",
                3
        );

        ParsedDocument parsedDoc = parsingAgent.parseDocument(parseInput);
        long parseDuration = System.currentTimeMillis() - parseStartTime;

        String parseModel = parsedDoc.model() != null ? parsedDoc.model() : "claude-haiku-4-5-20251001";
        int parseInTokens = parsedDoc.inputTokens() != null ? parsedDoc.inputTokens() : 0;
        int parseOutTokens = parsedDoc.outputTokens() != null ? parsedDoc.outputTokens() : 0;
        double parseCost = calculateCost(parseModel, parseInTokens, parseOutTokens);

        System.out.println("2. Document Parsing Agent Completed (LLM Call 1)");
        System.out.println("   Model Used: " + parseModel);
        System.out.println("   Tokens: Input=" + parseInTokens + ", Output=" + parseOutTokens);
        System.out.println("   Cost: $" + String.format("%.6f", parseCost) + " (" + parseDuration + " ms)");

        // Step 3: Corpus Matcher & Strategy Selection
        Map<String, Object> payload = parsedDoc.payload();
        Optional<GstCorpusMatcherService.CorpusMatchResult> matchOpt =
                corpusMatcherService.matchNotice(payload, ocrText);

        GstCorpusMatcherService.Strategy strategy = GstCorpusMatcherService.Strategy.HYBRID;
        String corpusSerial = "NONE";
        double matchScore = 0.0;
        if (matchOpt.isPresent()) {
            GstCorpusMatcherService.CorpusMatchResult match = matchOpt.get();
            strategy = match.strategy();
            corpusSerial = match.serial();
            matchScore = match.matchScore();
        }

        System.out.println("3. Strategy & Corpus Match Result:");
        System.out.println("   Strategy: " + strategy);
        System.out.println("   Corpus Serial: " + corpusSerial);
        System.out.println("   Similarity Score: " + String.format("%.4f", matchScore));

        // Step 4: Drafting Input Assembly
        Map<String, Object> noticeMap = new HashMap<>();
        noticeMap.put("document_type", payload.getOrDefault("document_type", "drc_01"));
        noticeMap.put("notice_number", payload.getOrDefault("notice_number", "SCN/2026/001"));
        noticeMap.put("din_or_rfn", payload.getOrDefault("din_or_rfn", "RFN-TEST0000000000"));
        noticeMap.put("authority", payload.getOrDefault("authority", "ASSISTANT COMMISSIONER, CGST (AUDIT) CIRCLE, SAMPLE CITY"));
        noticeMap.put("issue", payload.getOrDefault("summary_statement", "Demand under Section 73 & Section 74 for GSTR-2A/3B ITC Mismatch and Section 17(5) Blocked Credits"));

        String clientName = (String) payload.getOrDefault("taxpayer_name", "M/s Sunrise Polymers Pvt. Ltd.");
        String clientPan = (String) payload.getOrDefault("pan", "AAAAT1234A");
        String gstin = (String) payload.getOrDefault("gstin", "27AAAAT1234A1Z5");

        DraftingInput draftingInput = new DraftingInput(
                UUID.randomUUID(),
                "assertive",
                "Challenge Section 74 invocation for Para 2; establish lack of fraud or willful misstatement for trading business. For Para 1, rely on Section 16(2) and GSTR-2A vs 2B timeline.",
                clientName,
                clientPan,
                "Private Limited Company",
                "GST",
                gstin,
                "Maharashtra",
                "CGST/SGST Act, 2017",
                "2021-22 to 2023-24",
                "2022-23",
                noticeMap,
                payload,
                ocrText,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );

        // Step 5: Drafting Pipeline Service Execution (Pass 1 - RAG Store Empty)
        long draftStartTime1 = System.currentTimeMillis();
        DraftingPipelineResult pass1 = draftingPipelineService.runPipeline(draftingInput);
        long draftDuration1 = System.currentTimeMillis() - draftStartTime1;

        GeneratedDraft draft1 = pass1.draft();
        String draftModel1 = draft1.model() != null ? draft1.model() : "claude-haiku-4-5-20251001";
        int draftInTokens1 = pass1.totalInputTokens();
        int draftOutTokens1 = pass1.totalOutputTokens();
        double draftCost1 = calculateCost(draftModel1, draftInTokens1, draftOutTokens1);

        System.out.println("4. Drafting Pipeline Service Pass 1 Completed");
        System.out.println("   3-Tier Counts: rag_hit=" + pass1.ragHitCount() + " disk_cache_hit=" + pass1.diskCacheHitCount() + " opus_call=" + pass1.opusCallCount());
        System.out.println("   Stop Reason: " + pass1.stopReason());
        System.out.println("   Sections Generated: " + draft1.sections().size());
        System.out.println("   Tokens: Input=" + draftInTokens1 + ", Output=" + draftOutTokens1);

        // Step 6: Drafting Pipeline Service Execution (Pass 2 - RAG Store Populated)
        long draftStartTime2 = System.currentTimeMillis();
        DraftingPipelineResult pass2 = draftingPipelineService.runPipeline(draftingInput);
        long draftDuration2 = System.currentTimeMillis() - draftStartTime2;

        System.out.println("5. Drafting Pipeline Service Pass 2 Completed");
        System.out.println("   3-Tier Counts: rag_hit=" + pass2.ragHitCount() + " disk_cache_hit=" + pass2.diskCacheHitCount() + " opus_call=" + pass2.opusCallCount());
        System.out.println("   Stop Reason: " + pass2.stopReason());
        System.out.println("   Sections Generated: " + pass2.draft().sections().size());

        // Export Draft & Reports to ./output/
        File outputDir = new File("../output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        List<Map<String, Object>> rawSections = draft1.sections().stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("num", s.num());
            m.put("title", s.title());
            m.put("body_html", s.bodyHtml());
            return m;
        }).toList();

        DocxExportService.CoverSheetData cover = new DocxExportService.CoverSheetData(
                "development",
                clientName,
                clientPan,
                "GST",
                gstin,
                "Demand-cum-Show Cause Notice (Section 73/74)",
                "FY 2021-22 to 2023-24",
                "ASSISTANT COMMISSIONER, CENTRAL TAX (AUDIT) CIRCLE, SAMPLE CITY",
                "30 Days"
        );

        byte[] docxBytes = docxExportService.renderDraftDocx(
                "filing",
                cover,
                rawSections,
                "NoticeDesk Java 21 Pipeline — Verification Status: CITATIONS VERIFIED"
        );

        String docxPath = "../output/Meridian_Fabtech_SCN_Reply_Draft_Run1.docx";
        Files.write(Paths.get(docxPath), docxBytes);

        String txtPath = "../output/Meridian_Fabtech_SCN_Reply_Draft_Run1.txt";
        writeTxtDraft(txtPath, clientName, gstin, draft1);

        double totalCost = parseCost + draftCost1;
        long totalDuration = ocrDuration + parseDuration + draftDuration1;

        System.out.println("=================================================");
        System.out.println("=== PIPELINE EXECUTION SUMMARY ===");
        System.out.println("=================================================");
        System.out.println("Taxpayer: " + clientName + " (" + gstin + ")");
        System.out.println("Pass 1 3-Tier Counts: rag_hit=" + pass1.ragHitCount() + ", disk_cache_hit=" + pass1.diskCacheHitCount() + ", opus_call=" + pass1.opusCallCount());
        System.out.println("Pass 2 3-Tier Counts: rag_hit=" + pass2.ragHitCount() + ", disk_cache_hit=" + pass2.diskCacheHitCount() + ", opus_call=" + pass2.opusCallCount());
        System.out.println("Total LLM Cost: $" + String.format("%.6f", totalCost));
        System.out.println("Total Execution Time: " + totalDuration + " ms");
        System.out.println("Saved Word Document: " + new File(docxPath).getCanonicalPath());
        System.out.println("=================================================");

        assertNotNull(docxBytes);
    }

    private String extractPdfText(String pdfPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("pdftotext", pdfPath, "-");
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        p.waitFor();
        return sb.toString();
    }

    private double calculateCost(String model, int inputTokens, int outputTokens) {
        String lower = model.toLowerCase();
        double inputRate = 3.00 / 1_000_000.0;  // Default: Sonnet 3.5 ($3/1M)
        double outputRate = 15.00 / 1_000_000.0; // Default: Sonnet 3.5 ($15/1M)

        if (lower.contains("opus")) {
            inputRate = 15.00 / 1_000_000.0;  // Opus ($15/1M)
            outputRate = 75.00 / 1_000_000.0; // Opus ($75/1M)
        } else if (lower.contains("haiku")) {
            inputRate = 0.80 / 1_000_000.0;   // Haiku ($0.80/1M)
            outputRate = 4.00 / 1_000_000.0;  // Haiku ($4/1M)
        }

        return (inputTokens * inputRate) + (outputTokens * outputRate);
    }

    private void writeTxtDraft(String filepath, String clientName, String gstin, GeneratedDraft draft) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=================================================================\n");
        sb.append("NOTICE DESK LEGAL DRAFTING ENGINE — WRITTEN REPLY TO SCN\n");
        sb.append("=================================================================\n");
        sb.append("Taxpayer Name: ").append(clientName).append("\n");
        sb.append("GSTIN: ").append(gstin).append("\n");
        sb.append("Model Used: ").append(draft.model()).append("\n");
        sb.append("=================================================================\n\n");

        for (DraftSection s : draft.sections()) {
            sb.append("SECTION ").append(s.num()).append(": ").append(s.title()).append("\n");
            sb.append("-----------------------------------------------------------------\n");
            String text = s.bodyHtml()
                    .replaceAll("<p>", "")
                    .replaceAll("</p>", "\n\n")
                    .replaceAll("<br/?>", "\n")
                    .replaceAll("<b>", "")
                    .replaceAll("</b>", "")
                    .replaceAll("<div[^>]*>", "")
                    .replaceAll("</div>", "\n");
            sb.append(text.trim()).append("\n\n");
        }

        Files.writeString(Paths.get(filepath), sb.toString());
    }

    private void writePipelineReport(
            String filepath,
            String clientName, String gstin,
            String parseModel, int parseInTokens, int parseOutTokens, double parseCost, long parseDuration,
            String draftModel, int draftInTokens, int draftOutTokens, double draftCost, long draftDuration,
            GstCorpusMatcherService.Strategy strategy, String corpusSerial, double matchScore
    ) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# NoticeDesk Java Pipeline Execution & Cost Report\n\n");
        sb.append("**Target File**: `SCN_Test_Sample_ITC_Mismatch.pdf`\n");
        sb.append("**Taxpayer**: ").append(clientName).append(" (`").append(gstin).append("`)\n");
        sb.append("**Execution Engine**: NoticeDesk Java 21 / Spring Boot API\n");
        sb.append("**Date**: ").append(new java.util.Date()).append("\n\n");

        sb.append("## LLM Call Breakdown & Costs\n\n");
        sb.append("| Call Stage | Model Name | Input Tokens | Output Tokens | Duration | Cost (USD) |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");
        sb.append("| **1. Document Parsing Agent** | `").append(parseModel).append("` | ").append(parseInTokens).append(" | ").append(parseOutTokens).append(" | ").append(parseDuration).append(" ms | **$").append(String.format("%.6f", parseCost)).append("** |\n");
        sb.append("| **2. Legal Drafting Engine** | `").append(draftModel).append("` | ").append(draftInTokens).append(" | ").append(draftOutTokens).append(" | ").append(draftDuration).append(" ms | **$").append(String.format("%.6f", draftCost)).append("** |\n");
        sb.append("| **TOTAL** | — | **").append(parseInTokens + draftInTokens).append("** | **").append(parseOutTokens + draftOutTokens).append("** | **").append(parseDuration + draftDuration).append(" ms** | **$").append(String.format("%.6f", parseCost + draftCost)).append("** |\n\n");

        sb.append("## Corpus & Strategy Classification\n\n");
        sb.append("- **Strategy Assigned**: `").append(strategy).append("`\n");
        sb.append("- **Matched Corpus Serial**: `").append(corpusSerial).append("`\n");
        sb.append("- **Similarity Score**: `").append(String.format("%.4f", matchScore)).append("`\n\n");

        sb.append("## Output Artifacts Generated\n\n");
        sb.append("- **Microsoft Word (.docx)**: `output/SCN_Test_Sample_ITC_Mismatch_Draft.docx`\n");
        sb.append("- **Plain Text (.txt)**: `output/SCN_Test_Sample_ITC_Mismatch_Draft.txt`\n");
        sb.append("- **Pipeline Report**: `output/SCN_Test_Sample_ITC_Mismatch_Pipeline_Report.txt`\n");

        Files.writeString(Paths.get(filepath), sb.toString());
    }
}
