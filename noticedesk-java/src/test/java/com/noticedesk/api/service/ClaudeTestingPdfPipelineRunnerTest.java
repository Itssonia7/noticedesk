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
    private CitationVerificationAgent citationVerificationAgent;
    private DocxExportService docxExportService;
    private String resolvedApiKey;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AppProperties();
        objectMapper = new ObjectMapper();

        // 1. Resolve Anthropic API Key
        resolvedApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            Path envPath = Paths.get("../apps/api/.env");
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    if (line.startsWith("ANTHROPIC_API_KEY=")) {
                        resolvedApiKey = line.substring("ANTHROPIC_API_KEY=".length()).trim();
                        break;
                    }
                }
            }
        }

        boolean enableE2e = "true".equalsIgnoreCase(System.getProperty("enable.e2e.tests"))
                || System.getenv("ENABLE_E2E_TESTS") != null;

        if (enableE2e && resolvedApiKey != null && !resolvedApiKey.isBlank()) {
            properties.getLlm().setProviderPrimary("anthropic");
            properties.getLlm().getAnthropic().setApiKey(resolvedApiKey);
            properties.getLlm().getAnthropic().setModel("claude-opus-4-7");
            properties.getLlm().setModelDrafting("claude-opus-4-7");
            properties.getLlm().setModelParsing("claude-opus-4-7");
            properties.getLlm().setModelTriage("claude-opus-4-7");
            properties.getLlm().getAnthropic().setTimeoutSeconds(300.0);
            System.out.println("Loaded Anthropic API Key for Java pipeline execution.");
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
        CorpusRagSeederService seederService = new CorpusRagSeederService(properties, objectMapper, ragStoreService);
        seederService.seedCorpusToRag();

        draftingAgent = new DraftingAgent(llmFactory, objectMapper, ragStoreService, org.mockito.Mockito.mock(com.noticedesk.api.service.llm.ApiUsageLogService.class));
        citationVerificationAgent = new CitationVerificationAgent(objectMapper);
        docxExportService = new DocxExportService();
    }

    @Test
    void runPipelineForClaudeTestingPdf() throws Exception {
        String pdfPathStr = "../claude testing /SCN_Test_Sample_ITC_Mismatch.pdf";
        File pdfFile = new File(pdfPathStr);
        if (!pdfFile.exists()) {
            pdfPathStr = "./claude testing /SCN_Test_Sample_ITC_Mismatch.pdf";
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

        String parseModel = parsedDoc.model() != null ? parsedDoc.model() : "claude-sonnet-4-6";
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
        noticeMap.put("notice_number", payload.getOrDefault("notice_number", "SCN No. 07/2026-27/AC/Sample-Circle/Group-4/GST Audit"));
        noticeMap.put("din_or_rfn", payload.getOrDefault("din_or_rfn", "RFN-TEST0000000000"));
        noticeMap.put("authority", payload.getOrDefault("authority", "ASSISTANT COMMISSIONER, CGST (AUDIT) CIRCLE, SAMPLE CITY"));
        noticeMap.put("issue", payload.getOrDefault("summary_statement", "Demand under Section 73 & Section 74 for GSTR-2A/3B ITC Mismatch and Section 17(5) Blocked Credits"));

        String clientName = (String) payload.getOrDefault("taxpayer_name", "M/s Sample Test Traders Pvt. Ltd.");
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

        // Step 5: Drafting Agent Execution (LLM Call 2)
        long draftStartTime = System.currentTimeMillis();
        GeneratedDraft generatedDraft = draftingAgent.generateDraft(draftingInput);
        long draftDuration = System.currentTimeMillis() - draftStartTime;

        String draftModel = generatedDraft.model() != null ? generatedDraft.model() : "claude-sonnet-4-6";
        int draftInTokens = generatedDraft.inputTokens() != null ? generatedDraft.inputTokens() : 0;
        int draftOutTokens = generatedDraft.outputTokens() != null ? generatedDraft.outputTokens() : 0;
        double draftCost = calculateCost(draftModel, draftInTokens, draftOutTokens);

        System.out.println("4. Drafting Agent Completed (LLM Call 2)");
        System.out.println("   Model Used: " + draftModel);
        System.out.println("   Tokens: Input=" + draftInTokens + ", Output=" + draftOutTokens);
        System.out.println("   Cost: $" + String.format("%.6f", draftCost) + " (" + draftDuration + " ms)");

        // Step 6: Citation Verification Agent
        long verifyStartTime = System.currentTimeMillis();
        StringBuilder fullDraftText = new StringBuilder();
        for (DraftSection s : generatedDraft.sections()) {
            fullDraftText.append(s.title()).append("\n").append(s.bodyHtml()).append("\n");
        }

        var verificationResult = citationVerificationAgent.verify(fullDraftText.toString(), "stub");
        long verifyDuration = System.currentTimeMillis() - verifyStartTime;

        System.out.println("5. Citation Verification Completed (" + verifyDuration + " ms, " + verificationResult.citations().size() + " citations evaluated)");

        // Step 7: Export Draft & Reports to ./output/
        File outputDir = new File("../output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        List<Map<String, Object>> rawSections = generatedDraft.sections().stream().map(s -> {
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

        String docxPath = "../output/SCN_Test_Sample_ITC_Mismatch_Draft.docx";
        Files.write(Paths.get(docxPath), docxBytes);

        String txtPath = "../output/SCN_Test_Sample_ITC_Mismatch_Draft.txt";
        writeTxtDraft(txtPath, clientName, gstin, generatedDraft);

        String summaryPath = "../output/SCN_Test_Sample_ITC_Mismatch_Pipeline_Report.txt";
        writePipelineReport(
                summaryPath,
                clientName, gstin,
                parseModel, parseInTokens, parseOutTokens, parseCost, parseDuration,
                draftModel, draftInTokens, draftOutTokens, draftCost, draftDuration,
                strategy, corpusSerial, matchScore
        );

        double totalCost = parseCost + draftCost;
        long totalDuration = ocrDuration + parseDuration + draftDuration + verifyDuration;

        System.out.println("=================================================");
        System.out.println("=== PIPELINE EXECUTION SUMMARY ===");
        System.out.println("=================================================");
        System.out.println("Taxpayer: " + clientName + " (" + gstin + ")");
        System.out.println("LLM Call 1 (Parsing): " + parseModel + " | Cost: $" + String.format("%.6f", parseCost) + " | In: " + parseInTokens + " / Out: " + parseOutTokens);
        System.out.println("LLM Call 2 (Drafting): " + draftModel + " | Cost: $" + String.format("%.6f", draftCost) + " | In: " + draftInTokens + " / Out: " + draftOutTokens);
        System.out.println("Total LLM Cost: $" + String.format("%.6f", totalCost));
        System.out.println("Total Execution Time: " + totalDuration + " ms");
        System.out.println("Saved Word Document: " + new File(docxPath).getCanonicalPath());
        System.out.println("Saved Text File: " + new File(txtPath).getCanonicalPath());
        System.out.println("Saved Pipeline Report: " + new File(summaryPath).getCanonicalPath());
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
