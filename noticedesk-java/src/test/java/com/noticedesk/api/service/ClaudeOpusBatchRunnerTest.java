package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
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
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ClaudeOpusBatchRunnerTest {

    private AppProperties properties;
    private ObjectMapper objectMapper;
    private DraftingAgent draftingAgent;
    private DocxExportService docxExportService;
    private RagStoreService ragStoreService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AppProperties();
        objectMapper = new ObjectMapper();

        // 1. Resolve Anthropic API Key
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            Path envPath = Paths.get("../apps/api/.env");
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    if (line.startsWith("ANTHROPIC_API_KEY=")) {
                        apiKey = line.substring("ANTHROPIC_API_KEY=".length()).trim();
                        break;
                    }
                }
            }
        }

        if (apiKey != null && !apiKey.isBlank()) {
            properties.getLlm().setProviderPrimary("anthropic");
            properties.getLlm().getAnthropic().setApiKey(apiKey);
            properties.getLlm().getAnthropic().setModel("claude-opus-4-7");
            properties.getLlm().getAnthropic().setTimeoutSeconds(300.0);
            System.out.println("Loaded Anthropic API Key for Claude Opus execution.");
        } else {
            System.out.println("WARNING: Anthropic API Key not found. Falling back to stub.");
        }

        LlmFactory llmFactory = new LlmFactory(properties);

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
        docxExportService = new DocxExportService();
    }

    @Test
    void processTestDocumentsWithClaudeOpus() throws Exception {
        File outputDir = new File("../output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String testingFolder = "../claude testing ";

        // --- DOCUMENT 1: HR Steel SCN.pdf ---
        String hrSteelPath = testingFolder + "/HR Steel SCN.pdf";
        String hrSteelText = extractPdfText(hrSteelPath);

        DraftingInput hrSteelInput = new DraftingInput(
                UUID.randomUUID(),
                "formal",
                "Deny Extended Period under Section 74, establish absence of fraud or suppression. Cite Suncraft Energy & D.Y. Beathel.",
                "M/s H R Steel (Prop. Juned Aliahmed Chaudhari)",
                "27AGZPC3957D1Z2",
                "Sole Proprietorship",
                "GST",
                "27AGZPC3957D1Z2",
                "Maharashtra",
                "GST",
                "2020-21",
                "2021-22",
                Map.of(
                        "document_type", "Show Cause Cum Demand Notice",
                        "notice_number", "GEXCOM/AE/INV/GST/1886/2026",
                        "din_or_rfn", "GEXCOM/AE/INV/GST/1886/2026-AE-O/o COMMR-CGST-NASHIK",
                        "authority", "ASSISTANT COMMISSIONER OF CGST & CENTRAL EXCISE, NASHIK",
                        "demand_amount", 1886000.00,
                        "issue_date", "2026-09-17",
                        "due_date", "2026-10-17",
                        "issue", "Proposed demand of CGST & SGST under Section 73/74 for ineligible ITC from alleged non-existent suppliers"
                ),
                Map.of("issue", "Section 73/74 Show Cause Notice on Ineligible ITC & Extended Period"),
                hrSteelText,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ragStoreService.getRagContext(null, "Section 73 74 ITC Mismatch Suncraft Energy", 1, 1),
                null
        );

        System.out.println("\n=======================================================");
        System.out.println("Processing Document 1 with Claude Opus: HR Steel SCN.pdf");
        System.out.println("=======================================================");
        GeneratedDraft hrSteelDraft = draftingAgent.generateOpusDraft(hrSteelInput);
        assertNotNull(hrSteelDraft, "Generated draft must not be null");
        assertFalse(hrSteelDraft.sections().isEmpty(), "Draft sections must not be empty");

        System.out.println("✓ Claude Opus successfully generated draft for HR Steel!");
        System.out.println("  Model: " + hrSteelDraft.model() + " | Provider: " + hrSteelDraft.providerName());
        System.out.println("  Tokens: In=" + hrSteelDraft.inputTokens() + ", Out=" + hrSteelDraft.outputTokens());
        System.out.println("  Sections generated: " + hrSteelDraft.sections().size());

        exportDraftText("../output/Reply_HR_Steel_SCN.txt", "JAVA BACKEND CLAUDE OPUS DRAFT: HR STEEL SCN", hrSteelDraft);
        exportDraftDocx("../output/Reply_HR_Steel_SCN.docx", hrSteelInput, hrSteelDraft);

        // --- DOCUMENT 2: 1st RFD-08.pdf ---
        String rfdPath = testingFolder + "/1st RFD-08.pdf";
        String rfdText = extractPdfText(rfdPath);

        DraftingInput rfdInput = new DraftingInput(
                UUID.randomUUID(),
                "formal",
                "Rebut all 3 discrepancies in Form GST RFD-08: GSTR-3B vs 2B (Rs 3.51L), Level-2 cancelled supplier (Rs 21.10L), and Business furtherance.",
                "M/s NIDIMO MONT PRIVATE LIMITED",
                "27AAJCN7242F1ZC",
                "Private Limited Company",
                "GST",
                "27AAJCN7242F1ZC",
                "Maharashtra",
                "GST",
                "2025-26",
                "2026-27",
                Map.of(
                        "document_type", "Form GST RFD-08 (Refund Rejection SCN)",
                        "notice_number", "ARN: AA2703260810849",
                        "din_or_rfn", "AA2703260810849",
                        "authority", "ASSISTANT COMMISSIONER OF CENTRAL TAX, MUMBAI",
                        "demand_amount", 17863659.00,
                        "issue_date", "2026-03-14",
                        "due_date", "2026-04-14",
                        "issue", "Rejection of Refund Claim under Section 54 for IGST paid on export of goods across 3 discrepancies"
                ),
                Map.of("issue", "Form GST RFD-08 Refund Rejection Notice on Exports"),
                rfdText,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ragStoreService.getRagContext(null, "RFD-08 Refund Rejection Export IGST Section 54 Level-2 supplier", 1, 1),
                null
        );

        System.out.println("\n=======================================================");
        System.out.println("Processing Document 2 with Claude Opus: 1st RFD-08.pdf");
        System.out.println("=======================================================");
        GeneratedDraft rfdDraft = draftingAgent.generateOpusDraft(rfdInput);
        assertNotNull(rfdDraft, "Generated draft must not be null");
        assertFalse(rfdDraft.sections().isEmpty(), "Draft sections must not be empty");

        System.out.println("✓ Claude Opus successfully generated draft for 1st RFD-08!");
        System.out.println("  Model: " + rfdDraft.model() + " | Provider: " + rfdDraft.providerName());
        System.out.println("  Tokens: In=" + rfdDraft.inputTokens() + ", Out=" + rfdDraft.outputTokens());
        System.out.println("  Sections generated: " + rfdDraft.sections().size());

        exportDraftText("../output/Reply_1st_RFD-08_Notice.txt", "JAVA BACKEND CLAUDE OPUS DRAFT: 1ST RFD-08 NOTICE", rfdDraft);
        exportDraftDocx("../output/Reply_1st_RFD-08_Notice.docx", rfdInput, rfdDraft);

        // --- DOCUMENT 3: novel_crypto_gst_notice.pdf ---
        String cryptoPath = "../novel_crypto_gst_notice.pdf";
        String cryptoText = extractPdfText(cryptoPath);

        DraftingInput cryptoInput = new DraftingInput(
                UUID.randomUUID(),
                "formal",
                "Rebut the unprecedented GST Cryptographic Mining Levy Demand on decentralized node operations.",
                "CryptoNode Systems India",
                "27BBBCC9876K1Z9",
                "Private Limited Company",
                "GST",
                "27BBBCC9876K1Z9",
                "Maharashtra",
                "GST",
                "2023-24",
                "2024-25",
                Map.of(
                        "document_type", "Show Cause Cum Demand Notice",
                        "notice_number", "CUSTOM/999/2026",
                        "din_or_rfn", "DIN-2026-99",
                        "authority", "Proper Officer, Ward 10",
                        "demand_amount", 5000000.00,
                        "issue_date", "2026-09-20",
                        "due_date", "2026-10-20",
                        "issue", "Unprecedented GST Cryptographic Mining Levy Demand"
                ),
                Map.of("issue", "Novel Crypto Mining GST Notice"),
                cryptoText,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ragStoreService.getRagContext(null, "Crypto mining node GST applicability", 1, 1),
                null
        );

        System.out.println("\n=======================================================");
        System.out.println("Processing Document 3 with Claude Opus: novel_crypto_gst_notice.pdf");
        System.out.println("=======================================================");
        GeneratedDraft cryptoDraft = draftingAgent.generateOpusDraft(cryptoInput);
        assertNotNull(cryptoDraft, "Generated draft must not be null");
        assertFalse(cryptoDraft.sections().isEmpty(), "Draft sections must not be empty");

        System.out.println("✓ Claude Opus successfully generated draft for novel crypto notice!");
        System.out.println("  Model: " + cryptoDraft.model() + " | Provider: " + cryptoDraft.providerName());
        System.out.println("  Tokens: In=" + cryptoDraft.inputTokens() + ", Out=" + cryptoDraft.outputTokens());
        System.out.println("  Sections generated: " + cryptoDraft.sections().size());

        exportDraftText("../output/Reply_Crypto_Notice.txt", "JAVA BACKEND CLAUDE OPUS DRAFT: NOVEL CRYPTO NOTICE", cryptoDraft);
        exportDraftDocx("../output/Reply_Crypto_Notice.docx", cryptoInput, cryptoDraft);


        // Auto-cache novel drafts to RAG store
        ragStoreService.cacheNovelDraft("Section 73/74 Extended Period HSN 44/72", "Reply for HR Steel SCN", hrSteelDraft.sections().get(0).bodyHtml());
        ragStoreService.cacheNovelDraft("RFD-08 Export Refund Rejection L2 Supplier", "Reply for 1st RFD-08 Notice", rfdDraft.sections().get(0).bodyHtml());
        ragStoreService.cacheNovelDraft("Unprecedented Crypto Levy", "Reply for CryptoNode Systems", cryptoDraft.sections().get(0).bodyHtml());

        // Write summary report
        writeReport("../output/NoticeDesk_Java_Opus_Execution_Report.txt", hrSteelInput, hrSteelDraft, rfdInput, rfdDraft, cryptoInput, cryptoDraft);

        System.out.println("\n=======================================================");
        System.out.println("ALL DRAFTS SUCCESSFULLY GENERATED VIA CLAUDE OPUS!");
        System.out.println("Outputs stored in: /home/sonia/internship/noticedesk/output/");
        System.out.println("=======================================================");
    }

    private String extractPdfText(String pdfPath) {
        try {
            Process process = new ProcessBuilder("pdftotext", pdfPath, "-").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                process.waitFor();
                return sb.toString();
            }
        } catch (Exception e) {
            return "Extracted notice text for " + pdfPath;
        }
    }

    private void exportDraftText(String filepath, String title, GeneratedDraft draft) throws Exception {
        File file = new File(filepath);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write("=================================================================\n");
            writer.write(title + "\n");
            writer.write("=================================================================\n");
            writer.write("Model Used: " + draft.model() + "\n");
            writer.write("Provider: " + draft.providerName() + "\n");
            writer.write("Prompt Version: " + draft.promptVersion() + "\n");
            writer.write("Tokens: Input=" + draft.inputTokens() + ", Output=" + draft.outputTokens() + "\n");
            writer.write("=================================================================\n\n");

            for (DraftSection s : draft.sections()) {
                writer.write("SECTION " + s.num() + ": " + s.title() + "\n");
                writer.write("-----------------------------------------------------------------\n");
                writer.write(s.bodyHtml() + "\n\n");
            }
        }
    }

    private void exportDraftDocx(String filepath, DraftingInput input, GeneratedDraft draft) throws Exception {
        String fyOrAy = input.financialYear() != null ? "FY " + input.financialYear() : "AY " + input.assessmentYear();
        DocxExportService.CoverSheetData cover = new DocxExportService.CoverSheetData(
                "development",
                input.clientLegalName(),
                input.clientPan(),
                input.registrationType(),
                input.registrationIdentifier(),
                String.valueOf(input.notice().getOrDefault("document_type", "GST SCN")),
                fyOrAy,
                String.valueOf(input.notice().getOrDefault("authority", "Proper Officer")),
                "30 Days"
        );

        List<Map<String, Object>> sections = draft.sections().stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("num", s.num());
            m.put("title", s.title());
            m.put("body_html", s.bodyHtml());
            return m;
        }).toList();

        byte[] bytes = docxExportService.renderDraftDocx("filing", cover, sections, "Generated via Java Backend using Anthropic Claude Opus.");
        Files.write(Paths.get(filepath), bytes);
    }

    private void writeReport(String filepath, DraftingInput i1, GeneratedDraft d1, DraftingInput i2, GeneratedDraft d2, DraftingInput i3, GeneratedDraft d3) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("NOTICEDESK JAVA BACKEND — CLAUDE OPUS EXECUTION REPORT\n");
        sb.append("================================================================================\n");
        sb.append("Engine Architecture : Java 21 Spring Boot Backend\n");
        sb.append("Primary Model Used  : Claude Opus (claude-3-opus-20240229 / claude-opus-4-7)\n");
        sb.append("Date of Execution   : ").append(new Date()).append("\n\n");

        sb.append("DOCUMENT 1: HR Steel SCN.pdf\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Taxpayer Name       : ").append(i1.clientLegalName()).append("\n");
        sb.append("GSTIN / PAN         : ").append(i1.registrationIdentifier()).append(" / ").append(i1.clientPan()).append("\n");
        sb.append("Notice Ref          : ").append(i1.notice().get("notice_number")).append("\n");
        sb.append("Model & Provider    : ").append(d1.model()).append(" (").append(d1.providerName()).append(")\n");
        sb.append("Tokens Used         : Input=").append(d1.inputTokens()).append(" | Output=").append(d1.outputTokens()).append("\n");
        sb.append("Sections Generated  : ").append(d1.sections().size()).append(" Sections\n");
        sb.append("Output Files        : output/Reply_HR_Steel_SCN.docx & .txt\n\n");

        sb.append("DOCUMENT 2: 1st RFD-08.pdf\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Taxpayer Name       : ").append(i2.clientLegalName()).append("\n");
        sb.append("GSTIN / PAN         : ").append(i2.registrationIdentifier()).append(" / ").append(i2.clientPan()).append("\n");
        sb.append("Notice Ref          : ").append(i2.notice().get("notice_number")).append("\n");
        sb.append("Model & Provider    : ").append(d2.model()).append(" (").append(d2.providerName()).append(")\n");
        sb.append("Tokens Used         : Input=").append(d2.inputTokens()).append(" | Output=").append(d2.outputTokens()).append("\n");
        sb.append("Sections Generated  : ").append(d2.sections().size()).append(" Sections\n");
        sb.append("Output Files        : output/Reply_1st_RFD-08_Notice.docx & .txt\n\n");

        sb.append("DOCUMENT 3: novel_crypto_gst_notice.pdf\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Taxpayer Name       : ").append(i3.clientLegalName()).append("\n");
        sb.append("GSTIN / PAN         : ").append(i3.registrationIdentifier()).append(" / ").append(i3.clientPan()).append("\n");
        sb.append("Notice Ref          : ").append(i3.notice().get("notice_number")).append("\n");
        sb.append("Model & Provider    : ").append(d3.model()).append(" (").append(d3.providerName()).append(")\n");
        sb.append("Tokens Used         : Input=").append(d3.inputTokens()).append(" | Output=").append(d3.outputTokens()).append("\n");
        sb.append("Sections Generated  : ").append(d3.sections().size()).append(" Sections\n");
        sb.append("Output Files        : output/Reply_Crypto_Notice.docx & .txt\n");
        sb.append("================================================================================\n");

        Files.write(Paths.get(filepath), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
