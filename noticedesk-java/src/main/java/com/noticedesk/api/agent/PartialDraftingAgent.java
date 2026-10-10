package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.ReplyTemplate;
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

/**
 * v7 Stage 3 writer for PARTIAL matches: the catalogue card covers part of the issue; the model
 * writes the missing arguments and anchors them after template blocks that apply as-is.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartialDraftingAgent {

    public static final String PROMPT_VERSION = "partial_drafting_v2";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record PartialSectionAddition(
            int section,
            String afterBlock,
            String html
    ) {}

    public record RawAiCitation(
            String caseName,
            String court,
            Integer year,
            String quotedText,
            String citedFor
    ) {}

    public record PartialDraftingResult(
            List<PartialSectionAddition> sections,
            List<RawAiCitation> citations,
            List<String> documents,
            String summaryLine,
            String model,
            String promptVersion
    ) {
        public PartialDraftingResult(List<PartialSectionAddition> sections, List<RawAiCitation> citations) {
            this(sections, citations, List.of(), null, null, PROMPT_VERSION);
        }
    }

    public PartialDraftingResult generatePartialDrafting(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            ReplyTemplate template,
            String stageName,
            UUID noticeId
    ) {
        return generatePartialDrafting(issue, noticeInfo, template, stageName, noticeId, false);
    }

    /**
     * @param highStakes when true the high-stakes model ({@code LLM_MODEL_HIGH_STAKES}) writes the additions
     * @return the parsed additions, or {@code null} when the model call failed or returned unusable output
     *         (the caller keeps the [[PENDING_AI]] marker and raises a block flag)
     */
    public PartialDraftingResult generatePartialDrafting(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            ReplyTemplate template,
            String stageName,
            UUID noticeId,
            boolean highStakes
    ) {
        String agentKey = highStakes ? "high_stakes" : "partial_drafting";
        String model = properties.getLlm() != null
                ? (highStakes ? properties.getLlm().getModelHighStakes() : properties.getLlm().getModelPartialDrafting())
                : null;
        if (model == null || model.isBlank()) {
            String env = highStakes ? "LLM_MODEL_HIGH_STAKES" : "LLM_MODEL_PARTIAL_DRAFTING";
            throw new IllegalStateException(env + " is not configured. Set environment variable " + env + ".");
        }

        String provider = properties.getLlm().getProviderPrimary();
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("PartialDraftingAgent running in stub mode for issue {}", issue.issueNo());
            return stubResult(issue);
        }

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("issue", issue);
        userPayload.put("notice", noticeInfo);
        userPayload.put("template_id", template != null ? template.templateId() : null);
        userPayload.put("template_blocks", template != null ? template.blocks() : List.of());
        userPayload.put("stage", stageName);

        String systemPrompt = loadPrompt();
        String userPrompt;
        try {
            userPrompt = objectMapper.writeValueAsString(userPayload);
        } catch (Exception e) {
            userPrompt = userPayload.toString();
        }

        try {
            LlmProvider llmProvider = llmFactory.getLlmForAgent(agentKey);
            for (int attempt = 1; attempt <= 2; attempt++) {
                String prompt = attempt == 1 ? userPrompt : userPrompt + "\n\nCRITICAL: Output valid JSON only.";
                LlmResponse response = llmProvider.generateText(systemPrompt, prompt, 4096, 0.0);
                logUsage(response, model, agentKey, noticeId);
                PartialDraftingResult parsed = parseAndSanitize(response.content(),
                        response.model() != null ? response.model() : model);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("PartialDraftingAgent JSON parse failed on attempt {} for issue {}", attempt, issue.issueNo());
            }
            return null;
        } catch (Exception e) {
            log.error("PartialDraftingAgent execution failed for issue {}: {}", issue.issueNo(), e.getMessage());
            return null;
        }
    }

    private void logUsage(LlmResponse response, String model, String agentKey, UUID noticeId) {
        if (apiUsageLogService != null) {
            apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                    response.inputTokens() != null ? response.inputTokens() : 0,
                    response.outputTokens() != null ? response.outputTokens() : 0,
                    agentKey, null, noticeId);
        }
    }

    PartialDraftingResult parseAndSanitize(String json, String model) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);

            List<PartialSectionAddition> sections = new ArrayList<>();
            if (map.get("sections") instanceof List<?> secList) {
                for (Object item : secList) {
                    if (item instanceof Map<?, ?> m) {
                        int sec = m.get("section") != null ? Integer.parseInt(m.get("section").toString().trim()) : 4;
                        String after = AiOutputParser.str(m.get("after_block"));
                        String html = HtmlSanitizer.sanitize(m.get("html") != null ? m.get("html").toString() : "");
                        if (!html.isBlank()) {
                            sections.add(new PartialSectionAddition(sec, after, html));
                        }
                    }
                }
            }
            if (sections.isEmpty()) {
                return null;
            }

            return new PartialDraftingResult(
                    sections,
                    AiOutputParser.citations(map),
                    AiOutputParser.stringList(map, "documents"),
                    AiOutputParser.str(map.get("summary_line")),
                    model,
                    PROMPT_VERSION);
        } catch (Exception e) {
            log.warn("Failed to parse PartialDraftingAgent JSON output: {}", e.getMessage());
            return null;
        }
    }

    private PartialDraftingResult stubResult(MatchedIssue issue) {
        String html = "<p>[STUB - no model call] Additional argument for Issue #" + issue.issueNo()
                + " to be written by the partial-drafting model.</p>";
        return new PartialDraftingResult(
                List.of(new PartialSectionAddition(4, null, html)),
                List.of(), List.of(), null, "stub", PROMPT_VERSION);
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
