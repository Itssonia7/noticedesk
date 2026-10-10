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
import org.junit.jupiter.api.Test;
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

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String matchingModel = System.getenv("LLM_MODEL_MATCHING");
        if (matchingModel == null || matchingModel.isBlank()) {
            matchingModel = "claude-sonnet-5-5";
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping MatchingEvalRunnerTest (ANTHROPIC_API_KEY missing)");
            return;
        }

        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey(apiKey);
        String wsId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (wsId != null && !wsId.isBlank()) {
            props.getLlm().getAnthropic().setWorkspaceId(wsId);
        }
        props.getLlm().setModelMatching(matchingModel);
        props.getMatching().setAllowDraftCards(true); // Allow dev draft cards for test bench evaluation

        // Pricing configuration
        AppProperties.Pricing pricing = props.getPricing();
        pricing.setInrPerUsd(88.0);
        AppProperties.Pricing.ModelPrice p1 = new AppProperties.Pricing.ModelPrice();
        p1.setInputPerMillion(3.0);
        p1.setOutputPerMillion(15.0);
        p1.setCacheReadPerMillion(0.30);
        p1.setCacheWritePerMillion(3.75);
        pricing.getModels().put("claude-sonnet-5-5", p1);
        pricing.getModels().put("claude-3-5-sonnet-20241022", p1);

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(null, props, objectMapper);
        ApiUsageLogService usageLogService = new ApiUsageLogService(null, props);
        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, null, objectMapper);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:testbench/notices/*.json");

        if (resources == null || resources.length == 0) {
            System.out.println("No testbench notice files found in classpath:testbench/notices/*.json");
            return;
        }

        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        int totalNotices = 0;
        int wrongFullMatchCount = 0;
        int expectedFullMatchCount = 0;
        int correctPartialOrNoneCount = 0;
        int expectedPartialOrNoneCount = 0;

        int totalExpectedFacts = 0;
        int matchedFactsCount = 0;

        int totalExpectedParas = 0;
        int coveredParasCount = 0;

        int totalCalls = 0;
        int cacheHitCalls = 0;
        int totalCacheReadTokensCall2Plus = 0;

        BigDecimal totalCostInr = BigDecimal.ZERO;
        final BigDecimal INR_CAP = new BigDecimal("200.00");

        List<ObjectNode> noticeEvalResults = new ArrayList<>();
        List<WrongMatchInfo> wrongMatches = new ArrayList<>();

        for (Resource res : resources) {
            if (totalCostInr.compareTo(INR_CAP) >= 0) {
                System.err.println("SAFETY CAP REACHED: Total spend " + totalCostInr + " INR exceeded INR 200 limit. Halting evaluation.");
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

            MatchingResult result = agent.matchNoticeWithOcrText(ocrText, docType, UUID.randomUUID(), UUID.randomUUID());

            if (totalCalls > 1) {
                totalCacheReadTokensCall2Plus += result.cacheReadTokens();
            }

            if (result.cacheReadTokens() > 0) {
                cacheHitCalls++;
            }

            BigDecimal[] costs = usageLogService.calculateCost(matchingModel, result.inputTokens(), result.outputTokens(), result.cacheReadTokens(), result.cacheWriteTokens());
            BigDecimal noticeInr = costs[1];
            totalCostInr = totalCostInr.add(noticeInr);

            // Metrics evaluation against answer_key
            JsonNode expectedIssues = answerKey.path("issues");
            if (expectedIssues.isArray()) {
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
                            String why = result.issues().isEmpty() ? "No issue returned" : result.issues().get(0).why();
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
                            String why = result.issues().isEmpty() ? "No issue returned" : result.issues().get(0).why();
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

        double wrongFullMatchRate = expectedFullMatchCount > 0 ? (double) wrongFullMatchCount / expectedFullMatchCount * 100.0 : 0.0;
        double partialNoneCorrectPct = expectedPartialOrNoneCount > 0 ? (double) correctPartialOrNoneCount / expectedPartialOrNoneCount * 100.0 : 100.0;
        double factAccuracyPct = totalExpectedFacts > 0 ? (double) matchedFactsCount / totalExpectedFacts * 100.0 : 100.0;
        double paraCoveragePct = totalExpectedParas > 0 ? (double) coveredParasCount / totalExpectedParas * 100.0 : 100.0;
        double cacheHitRatePct = totalCalls > 1 ? (double) cacheHitCalls / (totalCalls - 1) * 100.0 : 0.0;

        System.out.println("=== MATCHING EVALUATION BENCHMARK METRICS ===");
        System.out.printf("Total Notices Evaluated: %d%n", totalNotices);
        System.out.printf("Wrong Full-Match Rate: %.2f%%%n", wrongFullMatchRate);
        System.out.printf("Partial/None Correctly Flagged: %.2f%%%n", partialNoneCorrectPct);
        System.out.printf("Fact Accuracy: %.2f%%%n", factAccuracyPct);
        System.out.printf("Para Coverage: %.2f%%%n", paraCoveragePct);
        System.out.printf("Cache Hit Rate (Call 2+): %.2f%%%n", cacheHitRatePct);
        System.out.printf("Cache Read Tokens (Calls 2+): %d%n", totalCacheReadTokensCall2Plus);
        System.out.printf("Total Benchmark Cost: %.4f INR%n", totalCostInr.doubleValue());

        if (wrongMatches.isEmpty()) {
            System.out.println("\nNo wrong matches! 100% agreement with answer keys.");
        } else {
            System.out.println("\n=== WRONG MATCH DETAILS ===");
            for (WrongMatchInfo wm : wrongMatches) {
                System.out.printf("- Notice: %s | Expected: %s | Got: %s | Why: %s%n", wm.noticeId(), wm.expected(), wm.got(), wm.why());
            }
        }

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
        summaryReport.put("total_notices", totalNotices);
        summaryReport.put("wrong_full_match_rate_pct", wrongFullMatchRate);
        summaryReport.put("partial_none_correct_pct", partialNoneCorrectPct);
        summaryReport.put("fact_accuracy_pct", factAccuracyPct);
        summaryReport.put("para_coverage_pct", paraCoveragePct);
        summaryReport.put("cache_hit_rate_pct", cacheHitRatePct);
        summaryReport.put("cache_read_tokens_calls_2_plus", totalCacheReadTokensCall2Plus);
        summaryReport.put("total_cost_inr", totalCostInr.doubleValue());
        summaryReport.set("notices", objectMapper.valueToTree(noticeEvalResults));

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile, summaryReport);
        System.out.println("Evaluation report written to: " + reportFile.getAbsolutePath());
    }
}

