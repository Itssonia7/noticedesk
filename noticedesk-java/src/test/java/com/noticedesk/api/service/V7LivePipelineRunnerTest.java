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
import com.noticedesk.api.testsupport.BenchNoticeRows;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
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

        // pricing, effort and max_tokens come from application.yml (+ env overrides)
        AppProperties props = TestAppProperties.fromApplicationYml();
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
        // synthetic bench notices only: log a 400-char sample of any unparseable writer output
        props.getLlm().setDebugLogRawOutput(true);
        System.out.printf("per-notice caps: high-stakes calls=%s, cost INR=%s%n",
                props.getLlm().getHighStakes().getMaxCallsPerNotice(), props.getLlm().getMaxCostInrPerNotice());

        for (String m : List.of(modelMatching, modelPartial, modelNewIssue, modelHighStakes)) {
            if (props.getPricing().getModels().get(m) == null) {
                System.err.println("WARNING: no price configured for " + m + " in application.yml; its calls show UNPRICED");
            }
        }
        for (String agent : List.of("matching", "partial_drafting", "new_issue", "high_stakes")) {
            System.out.printf("%s: effort=%s max_tokens=%d retry_max_tokens=%d%n", agent,
                    props.getLlm().effortForAgent(agent),
                    props.getLlm().maxTokensForAgent(agent, false), props.getLlm().maxTokensForAgent(agent, true));
        }

        ObjectMapper om = new ObjectMapper();

        // one connection for the whole run, so the RLS tenant set below applies to every insert
        org.springframework.jdbc.datasource.SingleConnectionDataSource ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:postgresql://127.0.0.1:5432/noticedesk_dev", "noticedesk", "noticedesk", true);
        ds.setDriverClassName("org.postgresql.Driver");
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds);
        UUID tenantUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        BenchNoticeRows.setTenant(jdbc.getJdbcTemplate(), tenantUuid);
        // dev issue cards + reply/stage templates (scn_74, scn_74a, anti_profiteering included). Skipped when
        // PRIVATE_SEED_DIR is set: the dev upserts must never overwrite a private catalogue.
        String privateSeedDir = System.getenv("PRIVATE_SEED_DIR");
        if (privateSeedDir == null || privateSeedDir.isBlank()) {
            java.sql.Connection conn = ds.getConnection();   // the single shared connection: not closed here
            for (String seed : List.of("seeds/dev/seed_issue_cards_dev.sql", "seeds/dev/seed_reply_and_stage_templates_dev.sql")) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn,
                        new org.springframework.core.io.FileSystemResource(seed));
            }
            System.out.println("Dev seeds loaded (issue cards, reply and stage templates)");
        } else {
            System.out.println("PRIVATE_SEED_DIR set: dev seeds not loaded");
        }

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(jdbc.getJdbcTemplate(), props, om);
        ApiUsageLogService usageLogService = new ApiUsageLogService(jdbc, props);
        RunCostLedger ledger = RunCostLedger.attach(usageLogService, System.out);

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
                partialAgent, newIssueAgent, highStakesAgent, citationAgent, props, usageLogService
        );

        String envNotices = System.getenv("V7_LIVE_NOTICES");
        if (envNotices == null || envNotices.isBlank()) {
            envNotices = System.getProperty("V7_LIVE_NOTICES");
        }
        String[] targetNotices;
        if (envNotices != null && !envNotices.isBlank()) {
            targetNotices = Arrays.stream(envNotices.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } else {
            targetNotices = new String[]{
                    "notice_02_fullmatch_sec74.json",
                    "notice_07_partial_novel_machinery.json",
                    "notice_10_nomatch_anti_profiteering.json"
            };
        }

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

            UUID noticeUuid = UUID.nameUUIDFromBytes(noticeId.getBytes(StandardCharsets.UTF_8));
            // issue_matches.notice_id references notices: create the synthetic notice row first
            BenchNoticeRows.ensureNotice(jdbc.getJdbcTemplate(), tenantUuid, noticeUuid, noticeId, gstin, clientName, docType, fy);

            DraftingAgent.DraftingInput input = new DraftingAgent.DraftingInput(
                    noticeUuid, "formal", null, clientName, null, null, "Regular", gstin, state,
                    "CGST Act", fy, null, noticeMap, Map.of(), ocr, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, null
            );

            System.out.printf("%n--- Calls for %s ---%n", noticeId);
            int callCheckpoint = ledger.size();
            DraftingPipelineResult result = pipelineService.runPipeline(input, tenantUuid, noticeUuid);

            // Model cost per notice from every billed call (failed attempts and retries included)
            double matchingInr = ledger.totalInrSince(callCheckpoint, "matching").doubleValue();
            double writerInr = ledger.totalInrSince(callCheckpoint).doubleValue() - matchingInr;
            // IndianKanoon calls are logged to the DB only (no token usage)
            Double ikInrObj = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(SUM(cost_inr), 0) FROM api_usage_logs WHERE notice_id = CAST(? AS UUID) AND provider = 'indiankanoon'",
                    Double.class, noticeUuid.toString());
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
        System.out.printf("Billed model calls: %d (failed: %d, unpriced: %d) | model cost: %.4f INR%n",
                ledger.size(), ledger.failedCalls(), ledger.unpricedCalls(), ledger.totalInr().doubleValue());
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
