package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class V7LivePipelineRunnerTest {

    private static final Map<String, String> STATE_BY_CODE = Map.of(
            "07", "Delhi",
            "24", "Gujarat",
            "27", "Maharashtra"
    );

    @Test
    void runV7LivePipelineRun2() throws Exception {
        String enableE2e = System.getenv("ENABLE_E2E_TESTS");
        if (!"true".equalsIgnoreCase(enableE2e) && !"true".equalsIgnoreCase(System.getProperty("ENABLE_E2E_TESTS"))) {
            System.out.println("Skipping V7LivePipelineRunnerTest (ENABLE_E2E_TESTS not set to true)");
            return;
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping V7LivePipelineRunnerTest (ANTHROPIC_API_KEY missing)");
            return;
        }

        String modelMatching = Optional.ofNullable(System.getenv("LLM_MODEL_MATCHING")).orElse("claude-sonnet-5-5");
        String modelPartial = Optional.ofNullable(System.getenv("LLM_MODEL_PARTIAL_DRAFTING")).orElse("claude-sonnet-5-5");
        String modelNewIssue = Optional.ofNullable(System.getenv("LLM_MODEL_NEW_ISSUE")).orElse("claude-opus-5-5");
        String modelHighStakes = Optional.ofNullable(System.getenv("LLM_MODEL_HIGH_STAKES")).orElse("claude-opus-5-5");
        String ikToken = Optional.ofNullable(System.getenv("INDIANKANOON_API_TOKEN")).orElse("");
        String citationProvider = (!ikToken.isBlank()) ? "indiankanoon" : "stub";

        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey(apiKey);
        String wsId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (wsId != null && !wsId.isBlank()) {
            props.getLlm().getAnthropic().setWorkspaceId(wsId);
        }

        props.getLlm().setModelMatching(modelMatching);
        props.getLlm().setModelPartialDrafting(modelPartial);
        props.getLlm().setModelNewIssue(modelNewIssue);
        props.getLlm().setModelHighStakes(modelHighStakes);

        props.getCitation().setProvider(citationProvider);
        props.getCitation().setIndiankanoonApiToken(ikToken);
        props.getMatching().setAllowDraftCards(true);

        // Pricing configuration
        AppProperties.Pricing pricing = props.getPricing();
        pricing.setInrPerUsd(88.0);

        AppProperties.Pricing.ModelPrice pSonnet = new AppProperties.Pricing.ModelPrice();
        pSonnet.setInputPerMillion(3.0);
        pSonnet.setOutputPerMillion(15.0);
        pSonnet.setCacheReadPerMillion(0.30);
        pSonnet.setCacheWritePerMillion(3.75);
        pricing.getModels().put("claude-sonnet-5-5", pSonnet);
        pricing.getModels().put("claude-3-5-sonnet-20241022", pSonnet);

        AppProperties.Pricing.ModelPrice pOpus = new AppProperties.Pricing.ModelPrice();
        pOpus.setInputPerMillion(15.0);
        pOpus.setOutputPerMillion(75.0);
        pOpus.setCacheReadPerMillion(1.50);
        pOpus.setCacheWritePerMillion(18.75);
        pricing.getModels().put("claude-opus-5-5", pOpus);
        pricing.getModels().put("claude-opus-4-7", pOpus);
        pricing.getModels().put("claude-3-opus-20240229", pOpus);

        ObjectMapper om = new ObjectMapper();

        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://127.0.0.1:5432/noticedesk_dev");
        ds.setUsername("noticedesk");
        ds.setPassword("noticedesk");
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds);

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(jdbc.getJdbcTemplate(), props, om);
        ApiUsageLogService usageLogService = new ApiUsageLogService(jdbc, props);

        IssueMatchingAgent matchingAgent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, jdbc, om);
        PartialDraftingAgent partialAgent = new PartialDraftingAgent(props, om, usageLogService, llmFactory);
        NewIssueDraftingAgent newIssueAgent = new NewIssueDraftingAgent(props, om, usageLogService, llmFactory);
        HighStakesDraftingAgent highStakesAgent = new HighStakesDraftingAgent(props, om, usageLogService, llmFactory);

        IndianKanoonClient ikClient = new IndianKanoonClient(om);
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, usageLogService, jdbc, ikClient);

        TemplateFillService fillService = new TemplateFillService(jdbc.getJdbcTemplate(), props, om);
        ReplyAssemblyService assemblyService = new ReplyAssemblyService(jdbc.getJdbcTemplate(), props, om);
        DraftCheckService checkService = new DraftCheckService(jdbc);

        V7DraftingPipelineService pipelineService = new V7DraftingPipelineService(
                matchingAgent, jdbc, om, fillService, assemblyService, checkService,
                partialAgent, newIssueAgent, highStakesAgent, citationAgent
        );

        String[] targetNotices = new String[]{
                "notice_07_partial_novel_machinery.json",
                "notice_10_nomatch_anti_profiteering.json",
                "notice_02_fullmatch_sec74.json"
        };

        File resultsDir = new File("../testbench/results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            resultsDir = new File("testbench/results");
            resultsDir.mkdirs();
        }

        BigDecimal totalRun2Inr = BigDecimal.ZERO;
        final BigDecimal INR_CAP = new BigDecimal("150.00");

        System.out.println("=== RUN 2: V7 LIVE PIPELINE EXECUTION REPORT ===");

        for (String noticeFile : targetNotices) {
            if (totalRun2Inr.compareTo(INR_CAP) >= 0) {
                System.err.println("SAFETY CAP REACHED: Run 2 spend " + totalRun2Inr + " INR exceeded INR 150 limit. Halting.");
                break;
            }

            ClassPathResource resource = new ClassPathResource("testbench/notices/" + noticeFile);
            String jsonText;
            try (InputStream is = resource.getInputStream()) {
                jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = om.readTree(jsonText);

            String noticeId = root.path("notice_id").asText();
            String docType = root.path("document_type").asText();
            String ocr = root.path("full_ocr_text").asText();
            JsonNode answerKey = root.path("answer_key");

            String ref = findPattern(ocr, "Reference No:\\s*(\\S+)");
            String date = findPattern(ocr, "\\nDate:\\s*(\\d{2}-\\d{2}-\\d{4})");
            String clientName = findPattern(ocr, "To,\\s*\\n(M/s [^\\n]+)");
            String gstin = findPattern(ocr, "GSTIN:\\s*([0-9A-Z]{15})");
            String state = STATE_BY_CODE.getOrDefault(gstin.substring(0, 2), "Unknown State");
            String fy = findPattern(ocr, "Financial Year:\\s*(\\S+)");
            String section = findPattern(ocr, "(?:Show Cause Notice|Notice) under (Section \\d+[A-Z]?)");

            Map<String, Object> noticeMap = new LinkedHashMap<>();
            noticeMap.put("notice_id", noticeId);
            noticeMap.put("tenant_id", UUID.fromString("11111111-1111-1111-1111-111111111111"));
            noticeMap.put("document_type", docType);
            noticeMap.put("notice_number", ref);
            noticeMap.put("issue_date", date);
            noticeMap.put("section", section);

            UUID tenantUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID noticeUuid = UUID.nameUUIDFromBytes(noticeId.getBytes(StandardCharsets.UTF_8));

            DraftingAgent.DraftingInput input = new DraftingAgent.DraftingInput(
                    noticeUuid, "formal", null, clientName, null, null, "Regular", gstin, state,
                    "CGST Act", fy, null, noticeMap, Map.of(), ocr, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, null
            );

            DraftingPipelineResult result = pipelineService.runPipeline(input, tenantUuid, noticeUuid);

            // Per notice cost calculations from DB logs
            Double matchingInrObj = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(SUM(cost_inr), 0) FROM api_usage_logs WHERE notice_id = CAST(? AS UUID) AND step = 'matching'",
                    Double.class, noticeUuid.toString());
            Double writerInrObj = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(SUM(cost_inr), 0) FROM api_usage_logs WHERE notice_id = CAST(? AS UUID) AND step NOT IN ('matching', 'citation')",
                    Double.class, noticeUuid.toString());
            Double ikInrObj = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(SUM(cost_inr), 0) FROM api_usage_logs WHERE notice_id = CAST(? AS UUID) AND step = 'citation'",
                    Double.class, noticeUuid.toString());

            double matchingInr = matchingInrObj != null ? matchingInrObj : 0.0;
            double writerInr = writerInrObj != null ? writerInrObj : 0.0;
            double ikInr = ikInrObj != null ? ikInrObj : 0.0;
            int matchingInTokens = result.totalInputTokens();
            int matchingOutTokens = result.totalOutputTokens();

            double noticeTotalInr = matchingInr + writerInr + ikInr;
            totalRun2Inr = totalRun2Inr.add(BigDecimal.valueOf(noticeTotalInr));

            String expectedStatus = answerKey.path("issues").path(0).path("status").asText("none");

            // Extract flags
            List<String> allFlags = new ArrayList<>();
            if (result.checkResults() != null) {
                for (DraftCheckResult c : result.checkResults()) {
                    if (!c.passed()) {
                        allFlags.add(c.checkName() + ":" + c.severity());
                    }
                }
            }

            // Extract citation statuses
            List<String> citStatuses = new ArrayList<>();
            if (result.citations() != null) {
                for (VerifiedCitation vc : result.citations()) {
                    citStatuses.add(vc.caseName() + " -> " + vc.status());
                }
            }

            // Render full draft text
            String fullDraftText = renderDraft(noticeId, result);

            // Save full draft text to testbench/results/<noticeId>_v7_draft.txt
            File draftFile = new File(resultsDir, noticeId + "_v7_draft.txt");
            Files.writeString(draftFile.toPath(), fullDraftText, StandardCharsets.UTF_8);

            System.out.printf("%n--- Notice: %s (%s) ---%n", noticeId, noticeFile);
            System.out.printf("Expected Status (Answer Key): %s%n", expectedStatus);
            System.out.printf("Matching Step: In=%d, Out=%d | INR=%.4f%n", matchingInTokens, matchingOutTokens, matchingInr);
            System.out.printf("Writer Step: INR=%.4f%n", writerInr);
            System.out.printf("IndianKanoon Step: INR=%.4f%n", ikInr);
            System.out.printf("Total Notice Cost: %.4f INR%n", noticeTotalInr);
            System.out.printf("Citation Statuses: %s%n", citStatuses.isEmpty() ? "None" : citStatuses);
            System.out.printf("Flags: %s%n", allFlags);
            System.out.printf("Saved draft to: %s%n", draftFile.getAbsolutePath());

            if ("notice_10_nomatch_anti_profiteering.json".equals(noticeFile)) {
                System.out.println("\n=== FULL DRAFT FOR NOTICE_10 ===");
                System.out.println(fullDraftText);
                System.out.println("=== END FULL DRAFT FOR NOTICE_10 ===\n");
            }
        }

        System.out.printf("%nTotal Run 2 Execution Cost: %.4f INR%n", totalRun2Inr.doubleValue());
    }

    private static String findPattern(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    private static String renderDraft(String noticeId, DraftingPipelineResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== V7 DRAFT FOR ").append(noticeId).append(" ===\n\n");
        for (DraftingAgent.DraftSection sec : result.draft().sections()) {
            sb.append("================================================================================\n");
            sb.append("SECTION ").append(sec.num()).append(": ").append(sec.title()).append("\n");
            sb.append("================================================================================\n");
            sb.append(htmlToText(sec.bodyHtml())).append("\n\n");
        }
        return sb.toString();
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return html.replaceAll("<!--[^>]*-->", "")
                .replace("<p>", "").replace("</p>", "\n")
                .replace("<h3>", "\n--- ").replace("</h3>", " ---\n")
                .replaceAll("<ul>|</ul>|<ol>|</ol>", "")
                .replace("<li>", "  * ").replace("</li>", "\n")
                .replaceAll("<table>|clusters", "")
                .replace("<tr>", "").replace("</tr>", "\n")
                .replace("<th>", "").replace("</th>", " | ")
                .replace("<td>", "").replace("</td>", " | ")
                .replace(" | \n", "\n")
                .replaceAll("<[^>]+>", "");
    }
}
