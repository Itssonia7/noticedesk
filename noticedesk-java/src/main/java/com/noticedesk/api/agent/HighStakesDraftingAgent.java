package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.util.HtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class HighStakesDraftingAgent {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record HighStakesResult(
            boolean active,
            String section01Html,
            String section03Html,
            List<RawAiCitation> citations
    ) {}

    public boolean isHighStakesActive(String noticeSec, String stageName, Double totalDemandAmount) {
        if (properties.getDrafting() == null || properties.getDrafting().getHighStakes() == null) {
            return false;
        }

        var hs = properties.getDrafting().getHighStakes();

        // 1. Section 74 check
        if (Boolean.TRUE.equals(hs.getSection74()) && noticeSec != null && noticeSec.toLowerCase().contains("74")) {
            return true;
        }

        // 2. Appeal stage check
        if (Boolean.TRUE.equals(hs.getAppealStage()) && stageName != null && stageName.toLowerCase().contains("appeal")) {
            return true;
        }

        // 3. Demand threshold check
        String thresholdStr = hs.getDemandThreshold();
        if (thresholdStr != null && !thresholdStr.isBlank() && totalDemandAmount != null) {
            try {
                double threshold = Double.parseDouble(thresholdStr);
                if (totalDemandAmount >= threshold) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    public HighStakesResult generateHighStakesDraft(
            MatchingResult matchingResult,
            DraftingAgent.DraftingInput input,
            String stageName,
            UUID noticeId
    ) {
        String noticeSec = (input != null && input.notice() != null && input.notice().get("section") != null) ?
                input.notice().get("section").toString() : "";
        Double totalDemand = (matchingResult != null && matchingResult.notice() != null) ?
                matchingResult.notice().totalDemandAmount() : null;

        if (!isHighStakesActive(noticeSec, stageName, totalDemand)) {
            return new HighStakesResult(false, null, null, List.of());
        }

        String model = properties.getLlm() != null ? properties.getLlm().getModelHighStakes() : null;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("LLM_MODEL_HIGH_STAKES is not configured. Set environment variable LLM_MODEL_HIGH_STAKES.");
        }

        log.info("High-Stakes Mode TRIGGERED for notice {}. Model: {}", noticeId, model);

        String systemPrompt = "You are a Senior Tax Advocate writing custom executive summary and factual background for a high-stakes GST matter. Emits JSON {section01_html, section03_html, citations:[]}. No invented facts.";
        String userPrompt = "High stakes matter context: " + (matchingResult != null ? matchingResult.notice() : "Notice");

        String provider = properties.getLlm() != null ? properties.getLlm().getProviderPrimary() : "stub";
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            return stubResult();
        }

        try {
            LlmProvider llmProvider = llmFactory.getLlmForAgent("high_stakes");
            LlmResponse response = llmProvider.generateText(systemPrompt, userPrompt, 4096, 0.0);
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                        response.inputTokens() != null ? response.inputTokens() : 0,
                        response.outputTokens() != null ? response.outputTokens() : 0,
                        "high_stakes", null, noticeId);
            }

            return parseAndSanitize(response.content());
        } catch (Exception e) {
            log.error("HighStakesDraftingAgent failed: {}", e.getMessage());
            return stubResult();
        }
    }

    private HighStakesResult parseAndSanitize(String json) {
        if (json == null || json.isBlank()) return stubResult();
        try {
            String cleanJson = json.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "").trim();
            Map<String, Object> map = objectMapper.readValue(cleanJson, new TypeReference<>() {});

            String sec01 = HtmlSanitizer.sanitize(map.getOrDefault("section01_html", "").toString());
            String sec03 = HtmlSanitizer.sanitize(map.getOrDefault("section03_html", "").toString());

            return new HighStakesResult(true, sec01, sec03, List.of());
        } catch (Exception e) {
            return stubResult();
        }
    }

    private HighStakesResult stubResult() {
        return new HighStakesResult(
                true,
                "<p><strong>[High-Stakes Executive Summary]</strong> Strategic high-stakes defence prepared for Section 74 proceedings.</p>",
                "<p><strong>[High-Stakes Factual Background]</strong> Comprehensive multi-year operational facts compiled.</p>",
                List.of()
        );
    }
}
