package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * v7 Stage 3 high-stakes mode: Section 74/74A proceedings, appeal stage, or demand at/above
 * {@code HIGH_STAKES_DEMAND_THRESHOLD} (only when that variable is set). The high-stakes model writes
 * the narrative of sections 01 and 03; the pipeline also routes the AI parts of 04/05/06 to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HighStakesDraftingAgent {

    public static final String PROMPT_VERSION = "high_stakes_v1";

    private static final Pattern SECTION_74 = Pattern.compile("(?i)(?:section|sec\\.?|u/s)?\\s*\\b74A?\\b");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record HighStakesResult(
            boolean active,
            String section01Html,
            String section03Html,
            List<RawAiCitation> citations,
            String model,
            String promptVersion
    ) {
        public HighStakesResult(boolean active, String section01Html, String section03Html, List<RawAiCitation> citations) {
            this(active, section01Html, section03Html, citations, null, PROMPT_VERSION);
        }
    }

    public boolean isHighStakesActive(String noticeSec, String stageName, Double totalDemandAmount) {
        if (properties.getDrafting() == null || properties.getDrafting().getHighStakes() == null) {
            return false;
        }
        var hs = properties.getDrafting().getHighStakes();

        if (Boolean.TRUE.equals(hs.getSection74()) && noticeSec != null && SECTION_74.matcher(noticeSec).find()) {
            return true;
        }
        if (Boolean.TRUE.equals(hs.getAppealStage()) && stageName != null && stageName.toLowerCase().contains("appeal")) {
            return true;
        }
        String thresholdStr = hs.getDemandThreshold();
        if (thresholdStr != null && !thresholdStr.isBlank() && totalDemandAmount != null) {
            try {
                if (totalDemandAmount >= Double.parseDouble(thresholdStr.trim())) {
                    return true;
                }
            } catch (NumberFormatException e) {
                log.warn("HIGH_STAKES_DEMAND_THRESHOLD is not a number; demand threshold check skipped");
            }
        }
        return false;
    }

    /**
     * Writes the high-stakes narrative for sections 01 and 03.
     *
     * @return the result, or {@code null} when the model call failed (the pipeline keeps the code-built
     *         sections and records the failure in Section 13)
     */
    public HighStakesResult generateHighStakesDraft(
            MatchingResult matchingResult,
            DraftingAgent.DraftingInput input,
            String stageName,
            UUID noticeId
    ) {
        String model = properties.getLlm() != null ? properties.getLlm().getModelHighStakes() : null;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("LLM_MODEL_HIGH_STAKES is not configured. Set environment variable LLM_MODEL_HIGH_STAKES.");
        }

        String provider = properties.getLlm().getProviderPrimary();
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("HighStakesDraftingAgent running in stub mode");
            return stubResult();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notice", matchingResult != null ? matchingResult.notice() : null);
        payload.put("issues", matchingResult != null ? matchingResult.issues() : List.of());
        payload.put("stage", stageName);
        if (input != null) {
            payload.put("notice_section", input.notice() != null ? input.notice().get("section") : null);
            payload.put("client_legal_name", input.clientLegalName());
            payload.put("gstin", input.registrationIdentifier());
            payload.put("state", input.registrationStateName());
            payload.put("financial_year", input.financialYear());
            payload.put("full_ocr_text", input.noticeOcrExcerpt());
        }

        try {
            String userPrompt = objectMapper.writeValueAsString(payload);
            LlmProvider llmProvider = llmFactory.getLlmForAgent("high_stakes");
            LlmResponse response = llmProvider.generateText(loadPrompt(), userPrompt, 4096, 0.0);
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                        response.inputTokens() != null ? response.inputTokens() : 0,
                        response.outputTokens() != null ? response.outputTokens() : 0,
                        "high_stakes", null, noticeId);
            }
            return parseAndSanitize(response.content(), response.model() != null ? response.model() : model);
        } catch (Exception e) {
            log.error("HighStakesDraftingAgent failed: {}", e.getMessage());
            return null;
        }
    }

    HighStakesResult parseAndSanitize(String json, String model) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);
            String sec01 = HtmlSanitizer.sanitize(Objects.toString(map.get("section01_html"), ""));
            String sec03 = HtmlSanitizer.sanitize(Objects.toString(map.get("section03_html"), ""));
            return new HighStakesResult(true, sec01, sec03, AiOutputParser.citations(map), model, PROMPT_VERSION);
        } catch (Exception e) {
            log.warn("Failed to parse HighStakesDraftingAgent JSON: {}", e.getMessage());
            return null;
        }
    }

    private HighStakesResult stubResult() {
        return new HighStakesResult(
                true,
                "<p>[STUB - no model call] High-stakes executive summary to be written by the high-stakes model.</p>",
                "<p>[STUB - no model call] High-stakes factual background to be written by the high-stakes model.</p>",
                List.of(), "stub", PROMPT_VERSION
        );
    }

    private String loadPrompt() {
        try {
            ClassPathResource res = new ClassPathResource("prompts/" + PROMPT_VERSION + ".md");
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Prompt prompts/" + PROMPT_VERSION + ".md missing from classpath", e);
        }
    }
}
