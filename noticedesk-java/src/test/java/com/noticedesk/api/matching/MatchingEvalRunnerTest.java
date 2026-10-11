package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

class MatchingEvalRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record WrongMatchInfo(String noticeId, String expected, String got, String why) {}

    @Test
    void runMatchingEvaluationBenchmark() throws Exception {
        String enableE2e = System.getenv("ENABLE_E2E_TESTS");
        if (!"true".equalsIgnoreCase(enableE2e) && !"true".equalsIgnoreCase(System.getProperty("ENABLE_E2E_TESTS"))) {
            System.out.println("Skipping MatchingEvalRunnerTest (ENABLE_E2E_TESTS not set to true)");
            return;
        }

        // ---- cost safeguards first: no call is made when any of these refuses the run
        LiveRunGuard guard = LiveRunGuard.fromEnvironment();
        LiveRunGuard.CommitCheck commit = guard.checkCommit(LiveRunGuard.gitHead());
        System.out.println(commit.message());
        if (!commit.allowed()) {
            fail(commit.message());
        }
        if (Boolean.TRUE.equals(LiveRunGuard.gitDirty())) {
            System.out.println("WARNING: tracked files have uncommitted changes; this run is not exactly commit " + commit.head());
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String matchingModel = System.getenv("LLM_MODEL_MATCHING");
        if (matchingModel == null || matchingModel.isBlank()) {
            System.out.println("Skipping MatchingEvalRunnerTest (LLM_MODEL_MATCHING not set)");
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping MatchingEvalRunnerTest (ANTHROPIC_API_KEY missing)");
            return;
        }

        // pricing, effort and max_tokens come from application.yml (+ env overrides)
        AppProperties props = TestAppProperties.fromApplicationYml();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey(apiKey);
        String wsId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (wsId != null && !wsId.isBlank()) {
            props.getLlm().getAnthropic().setWorkspaceId(wsId);
        }
        props.getLlm().setModelMatching(matchingModel);
        props.getMatching().setAllowDraftCards(true); // Allow dev draft cards for test bench evaluation

        if (!new ApiUsageLogService(null, props).hasPrice(matchingModel)) {
            // an unpriced model would make the cost caps blind
            fail("REFUSED: no price configured for model " + matchingModel + " under noticedesk.pricing.models in application.yml");
        }
        guard.printSettings(System.out);
        System.out.println("Matching effort=" + props.getLlm().effortForAgent("matching")
                + " max_tokens=" + props.getLlm().maxTokensForAgent("matching", false)
                + " retry_max_tokens=" + props.getLlm().maxTokensForAgent("matching", true));

        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://127.0.0.1:5432/noticedesk_dev");
        ds.setUsername("noticedesk");
        ds.setPassword("noticedesk");
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(ds);

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(jdbcTemplate, props, objectMapper);
        ApiUsageLogService usageLogService = new ApiUsageLogService(null, props);
        RunCostLedger ledger = RunCostLedger.attach(usageLogService, System.out);
        guard.attach(usageLogService);
        this.guard = guard;
        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, null, objectMapper);

        // Dummy set: the committed testbench (fake data). Private set: $PRIVATE_SEED_DIR/notices/*.json
        // (real data kept outside the repo). They are evaluated and reported separately.
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Resource> uniqueResources = new TreeMap<>();
        for (Resource res : resolver.getResources("classpath*:testbench/notices/*.json")) {
            uniqueResources.putIfAbsent(res.getFilename(), res);
        }
        List<Resource> dummyNotices = new ArrayList<>(uniqueResources.values());
        if (guard.smoke() && !dummyNotices.isEmpty()) {
            dummyNotices = List.of(dummyNotices.get(0));
            System.out.println("LIVE_SMOKE=true: only " + dummyNotices.get(0).getFilename() + " is run; private set skipped");
        }

        List<Resource> privateNotices = new ArrayList<>();
        String privateSeedDir = System.getenv("PRIVATE_SEED_DIR");
        if (privateSeedDir != null && !privateSeedDir.isBlank() && !guard.smoke()) {
            File dir = new File(privateSeedDir, "notices");
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) privateNotices.add(new org.springframework.core.io.FileSystemResource(f));
            }
            System.out.println("Private notice set: " + privateNotices.size() + " file(s)");
        } else {
            System.out.println("Private notice set skipped (PRIVATE_SEED_DIR not set)");
        }

        BigDecimal[] spentInr = {BigDecimal.ZERO};
        ObjectNode dummySummary = evaluateSet("dummy", dummyNotices, false, agent, ledger, spentInr);
        ObjectNode privateSummary = privateNotices.isEmpty() || abortReason != null ? null
                : evaluateSet("private", privateNotices, true, agent, ledger, spentInr);
        System.out.printf("Total billed calls: %d (failed: %d, unpriced: %d) | Total cost: %.4f INR (run cap INR %s, commit %s%s)%n",
                ledger.size(), ledger.failedCalls(), ledger.unpricedCalls(), ledger.totalInr().doubleValue(),
                guard.maxRunCostInr().toPlainString(), commit.head(), guard.smoke() ? ", SMOKE" : "");

        // Write timestamped JSON report to repo root testbench/results/
        File resultsDir = new File("../testbench/results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            resultsDir = new File("testbench/results");
            resultsDir.mkdirs();
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File reportFile = new File(resultsDir, "eval_matching_" + timestamp + ".json");

        ObjectNode summaryReport = objectMapper.createObjectNode();
        summaryReport.put("timestamp", timestamp);
        summaryReport.put("model", matchingModel);
        summaryReport.set("dummy", dummySummary);
        if (privateSummary != null) {
            summaryReport.set("private", privateSummary);   // notice IDs + metrics only
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile, summaryReport);
        System.out.println("Evaluation report written to: " + reportFile.getAbsolutePath());
        if (abortReason != null) {
            System.err.println("LIVE RUN ABORTED: " + abortReason);
            fail("LIVE RUN ABORTED: " + abortReason);
        }
    }

    private LiveRunGuard guard;
    /** Set when a billing / auth error or the consecutive-failure limit stopped the run. */
    private String abortReason;

    /**
     * Evaluates one notice set. The returned JSON holds only notice IDs and metrics; for the private
     * set nothing from the notice text or the model's explanation is printed or stored.
     */
    ObjectNode evaluateSet(String setName, List<Resource> noticeList, boolean isPrivate,
                           IssueMatchingAgent agent, RunCostLedger ledger, BigDecimal[] spentInr) throws Exception {

        int totalNotices = 0;
        int errorCallsCount = 0;
        int wrongFullMatchCount = 0;
        int expectedFullMatchCount = 0;
        int multiFullMatchCount = 0;
        int correctPartialOrNoneCount = 0;
        int expectedPartialOrNoneCount = 0;

        int totalExpectedFacts = 0;
        int matchedFactsCount = 0;

        int totalExpectedParas = 0;
        int coveredParasCount = 0;

        int totalCalls = 0;
        int cacheHitCalls = 0;
        int totalCacheReadTokensCall2Plus = 0;

        BigDecimal startInr = spentInr[0];      // the INR cap applies across both sets
        BigDecimal totalCostInr = startInr;
        final BigDecimal INR_CAP = guard != null ? guard.maxRunCostInr() : LiveRunGuard.DEFAULT_MAX_RUN_COST_INR;

        List<ObjectNode> noticeEvalResults = new ArrayList<>();
        List<WrongMatchInfo> wrongMatches = new ArrayList<>();

        for (Resource res : noticeList) {
            if (totalCostInr.compareTo(INR_CAP) >= 0) {
                System.err.println("SAFETY CAP REACHED: Total spend " + totalCostInr + " INR reached the run cap of INR " + INR_CAP + ". Halting evaluation.");
                break;
            }

            totalNotices++;
            totalCalls++;

            String jsonText;
            try (InputStream is = res.getInputStream()) {
                jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = objectMapper.readTree(jsonText);

            String noticeId = root.path("notice_id").asText("bench-notice");
            String docType = root.path("document_type").asText("scn_73");
            String ocrText = root.path("full_ocr_text").asText();
            JsonNode answerKey = root.path("answer_key");

            System.out.println("Notice " + noticeId + ":");
            int callCheckpoint = ledger.size();
            MatchingResult result;
            try {
                result = agent.matchNoticeWithOcrText(ocrText, docType, UUID.randomUUID(), UUID.randomUUID());
            } catch (LlmFatalException e) {
                // billing / auth error, or LIVE_MAX_CONSECUTIVE_FAILURES reached: stop the whole run now
                totalCostInr = totalCostInr.add(ledger.totalInrSince(callCheckpoint));
                abortReason = e.getMessage();
                errorCallsCount++;
                break;
            }

            // every billed call for this notice (failed attempts and retries included)
            BigDecimal noticeInr = ledger.totalInrSince(callCheckpoint);
            totalCostInr = totalCostInr.add(noticeInr);
            String whyStr = result.issues().stream().map(com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue::why).collect(java.util.stream.Collectors.joining(" ")).toLowerCase();
            // same inputs as the live pipeline: compare this hash with its matcher run when results differ
            System.out.printf("  catalogue_hash=%s status=%s cost=%.4f INR%n", result.catalogueHash(),
                    result.issues().stream().map(i -> i.status() + i.cardIds()).toList(), noticeInr.doubleValue());

            // Requirement 1: Failed/fallback match counted as an ERROR, never as correct "none"
            if (result.flags().contains("matching_failed") || result.flags().contains("matching_parse_failed")) {
                errorCallsCount++;
                wrongMatches.add(new WrongMatchInfo(noticeId, "Successful Match", "ERROR",
                        "Call failed or returned fallback result: " + result.flags() + (isPrivate ? "" : " | detail: " + whyStr)));
                continue;
            }

            if (totalCalls > 1) {
                totalCacheReadTokensCall2Plus += result.cacheReadTokens();
            }

            if (result.cacheReadTokens() > 0) {
                cacheHitCalls++;
            }

            // Metrics evaluation against answer_key
            JsonNode expectedIssues = answerKey.path("issues");
            if (expectedIssues.isArray()) {
                if (expectedIssues.size() > 1) {
                    multiFullMatchCount++;
                }

                for (JsonNode exp : expectedIssues) {
                    String expStatus = exp.path("status").asText();
                    String expCardId = exp.path("card_ids").toString();

                    if ("full".equalsIgnoreCase(expStatus)) {
                        expectedFullMatchCount++;
                        boolean matchedFull = result.issues().stream().anyMatch(i -> "full".equalsIgnoreCase(i.status()));
                        if (!matchedFull) {
                            wrongFullMatchCount++;
                            String gotStatus = result.issues().isEmpty() ? "none" : result.issues().get(0).status();
                            String gotCardId = result.issues().isEmpty() ? "none" : result.issues().get(0).cardIds().toString();
                            String why = result.issues().isEmpty() ? "No issue returned" : (isPrivate ? "(withheld: private notice)" : result.issues().get(0).why());
                            wrongMatches.add(new WrongMatchInfo(noticeId, "full (" + expCardId + ")", gotStatus + " (" + gotCardId + ")", why));
                        }
                    } else {
                        expectedPartialOrNoneCount++;
                        boolean matchedNonFull = result.issues().stream().anyMatch(i -> expStatus.equalsIgnoreCase(i.status()));
                        if (matchedNonFull) {
                            correctPartialOrNoneCount++;
                        } else {
                            String gotStatus = result.issues().isEmpty() ? "none" : result.issues().get(0).status();
                            String gotCardId = result.issues().isEmpty() ? "none" : result.issues().get(0).cardIds().toString();
                            String why = result.issues().isEmpty() ? "No issue returned" : (isPrivate ? "(withheld: private notice)" : result.issues().get(0).why());
                            wrongMatches.add(new WrongMatchInfo(noticeId, expStatus + " (" + expCardId + ")", gotStatus + " (" + gotCardId + ")", why));
                        }
                    }

                    totalExpectedFacts++;
                    if (!result.issues().isEmpty() && result.issues().get(0).facts() != null && !result.issues().get(0).facts().isEmpty()) {
                        matchedFactsCount++;
                    }
                }
            }

            int expectedParaCount = answerKey.path("para_count").asInt(1);
            totalExpectedParas += expectedParaCount;
            if (result.paraMap() != null && !result.paraMap().isEmpty()) {
                coveredParasCount += Math.min(result.paraMap().size(), expectedParaCount);
            }

            ObjectNode evalItem = objectMapper.createObjectNode();
            evalItem.put("notice_id", noticeId);
            evalItem.put("document_type", docType);
            evalItem.put("input_tokens", result.inputTokens());
            evalItem.put("output_tokens", result.outputTokens());
            evalItem.put("cache_read_tokens", result.cacheReadTokens());
            evalItem.put("cache_write_tokens", result.cacheWriteTokens());
            evalItem.put("cost_inr", noticeInr.doubleValue());
            noticeEvalResults.add(evalItem);
        }

        int successfulCalls = totalNotices - errorCallsCount;
        // With zero successful calls nothing was measured: report N/A, never 100%.
        String wrongFullMatchRate = RunCostLedger.pct(wrongFullMatchCount, expectedFullMatchCount, successfulCalls);
        String partialNoneCorrectPct = RunCostLedger.pct(correctPartialOrNoneCount, expectedPartialOrNoneCount, successfulCalls);
        String factAccuracyPct = RunCostLedger.pct(matchedFactsCount, totalExpectedFacts, successfulCalls);
        String paraCoveragePct = RunCostLedger.pct(coveredParasCount, totalExpectedParas, successfulCalls);
        String cacheHitRatePct = RunCostLedger.pct(cacheHitCalls, successfulCalls - 1, successfulCalls);
        BigDecimal setCostInr = totalCostInr.subtract(startInr);

        System.out.println("=== MATCHING EVALUATION BENCHMARK METRICS: " + setName.toUpperCase() + " SET ===");
        System.out.printf("Total Notices Evaluated: %d (Files Count: %d)%n", totalNotices, noticeList.size());
        System.out.printf("Successful Calls: %d | Errors/Failures: %d%n", successfulCalls, errorCallsCount);
        System.out.printf("Multi-Issue Full Matches (multi_full): %d%n", multiFullMatchCount);
        System.out.println("Wrong Full-Match Rate: " + wrongFullMatchRate);
        System.out.println("Partial/None Correctly Flagged: " + partialNoneCorrectPct);
        System.out.println("Fact Accuracy: " + factAccuracyPct);
        System.out.println("Para Coverage: " + paraCoveragePct);
        System.out.println("Cache Hit Rate (Call 2+): " + cacheHitRatePct);
        System.out.printf("Cache Read Tokens (Calls 2+): %d%n", totalCacheReadTokensCall2Plus);
        System.out.printf("Total Benchmark Cost (all billed calls, failed included): %.4f INR%n", setCostInr.doubleValue());

        if (successfulCalls == 0) {
            System.out.println("\nNo successful calls: accuracy metrics are N/A.");
        } else if (wrongMatches.isEmpty()) {
            System.out.println("\nNo wrong matches against the answer keys.");
        }
        if (!wrongMatches.isEmpty()) {
            System.out.println("\n=== WRONG MATCH DETAILS ===");
            for (WrongMatchInfo wm : wrongMatches) {
                System.out.printf("- Notice: %s | Expected: %s | Got: %s | Why: %s%n", wm.noticeId(), wm.expected(), wm.got(), wm.why());
            }
        }

        ObjectNode summaryReport = objectMapper.createObjectNode();
        summaryReport.put("set", setName);
        summaryReport.put("total_notices", totalNotices);
        summaryReport.put("successful_calls", successfulCalls);
        summaryReport.put("error_calls", errorCallsCount);
        summaryReport.put("multi_full_matches", multiFullMatchCount);
        // null = N/A (no successful calls / nothing to measure)
        summaryReport.put("wrong_full_match_rate_pct", RunCostLedger.pctValue(wrongFullMatchCount, expectedFullMatchCount, successfulCalls));
        summaryReport.put("partial_none_correct_pct", RunCostLedger.pctValue(correctPartialOrNoneCount, expectedPartialOrNoneCount, successfulCalls));
        summaryReport.put("fact_accuracy_pct", RunCostLedger.pctValue(matchedFactsCount, totalExpectedFacts, successfulCalls));
        summaryReport.put("para_coverage_pct", RunCostLedger.pctValue(coveredParasCount, totalExpectedParas, successfulCalls));
        summaryReport.put("cache_hit_rate_pct", RunCostLedger.pctValue(cacheHitCalls, successfulCalls - 1, successfulCalls));
        summaryReport.put("cache_read_tokens_calls_2_plus", totalCacheReadTokensCall2Plus);
        summaryReport.put("total_cost_inr", setCostInr.doubleValue());
        summaryReport.set("notices", objectMapper.valueToTree(noticeEvalResults));

        spentInr[0] = totalCostInr;
        return summaryReport;
    }
}
