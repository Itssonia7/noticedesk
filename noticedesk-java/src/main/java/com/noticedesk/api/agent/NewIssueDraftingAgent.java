package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
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

/**
 * v7 Stage 3 writer for issues with NO catalogue match: the model writes sections 04/05/06 for the
 * issue from the full notice text. The strength note is internal and goes to Section 13 only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewIssueDraftingAgent {

    public static final String PROMPT_VERSION = "new_issue_v2";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record NewIssueDraftingResult(
            int issueNo,
            String section04Html,
            String section05Html,
            String section06Html,
            List<RawAiCitation> citations,
            String strengthNote,
            List<String> documents,
            String summaryLine,
            String model,
            String promptVersion
    ) {
        public NewIssueDraftingResult(int issueNo, String section04Html, String section05Html, String section06Html,
                                      List<RawAiCitation> citations, String strengthNote) {
            this(issueNo, section04Html, section05Html, section06Html, citations, strengthNote,
                    List.of(), null, null, PROMPT_VERSION);
        }
    }

    /** One row of {@code ai_issue_sections}, saved by DraftingWorkflow after the draft row insert. */
    public record AiIssueSectionRecord(
            int issueNo,
            String model,
            String promptVersion,
            Map<String, Object> outputJson,
            String status
    ) {}

    public NewIssueDraftingResult generateNewIssueDraft(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            String fullOcrText,
            String stageName,
            UUID noticeId
    ) {
        return generateNewIssueDraft(issue, noticeInfo, fullOcrText, stageName, noticeId, false);
    }

    /**
     * @param highStakes when true the high-stakes model ({@code LLM_MODEL_HIGH_STAKES}) writes the issue
     * @return the parsed output, or {@code null} when the model call failed or returned unusable output
     */
    public NewIssueDraftingResult generateNewIssueDraft(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            String fullOcrText,
            String stageName,
            UUID noticeId,
            boolean highStakes
    ) {
        String agentKey = highStakes ? "high_stakes" : "new_issue";
        String model = properties.getLlm() != null
                ? (highStakes ? properties.getLlm().getModelHighStakes() : properties.getLlm().getModelNewIssue())
                : null;
        if (model == null || model.isBlank()) {
            String env = highStakes ? "LLM_MODEL_HIGH_STAKES" : "LLM_MODEL_NEW_ISSUE";
            throw new IllegalStateException(env + " is not configured. Set environment variable " + env + ".");
        }

        String provider = properties.getLlm().getProviderPrimary();
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("NewIssueDraftingAgent running in stub mode for issue {}", issue.issueNo());
            return stubResult(issue);
        }

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("issue", issue);
        userPayload.put("notice", noticeInfo);
        userPayload.put("full_ocr_text", fullOcrText);
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
                LlmResponse response = llmProvider.generateText(systemPrompt, prompt, 6000, 0.0);
                if (apiUsageLogService != null) {
                    apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                            response.inputTokens() != null ? response.inputTokens() : 0,
                            response.outputTokens() != null ? response.outputTokens() : 0,
                            agentKey, null, noticeId);
                }
                NewIssueDraftingResult parsed = parseAndSanitize(response.content(), issue.issueNo(),
                        response.model() != null ? response.model() : model);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("NewIssueDraftingAgent JSON parse failed on attempt {} for issue {}", attempt, issue.issueNo());
            }
            return null;
        } catch (Exception e) {
            log.error("NewIssueDraftingAgent execution failed for issue {}: {}", issue.issueNo(), e.getMessage());
            return null;
        }
    }

    NewIssueDraftingResult parseAndSanitize(String json, int issueNo, String model) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);

            String sec04 = HtmlSanitizer.sanitize(Objects.toString(map.get("section04_html"), ""));
            String sec05 = HtmlSanitizer.sanitize(Objects.toString(map.get("section05_html"), ""));
            String sec06 = HtmlSanitizer.sanitize(Objects.toString(map.get("section06_html"), ""));
            if (sec04.isBlank() && sec06.isBlank()) {
                return null;
            }

            return new NewIssueDraftingResult(issueNo, sec04, sec05, sec06,
                    AiOutputParser.citations(map),
                    AiOutputParser.str(map.get("strength_note")),
                    AiOutputParser.stringList(map, "documents"),
                    AiOutputParser.str(map.get("summary_line")),
                    model, PROMPT_VERSION);
        } catch (Exception e) {
            log.warn("Failed to parse NewIssueDraftingAgent JSON: {}", e.getMessage());
            return null;
        }
    }

    private NewIssueDraftingResult stubResult(MatchedIssue issue) {
        return new NewIssueDraftingResult(
                issue.issueNo(),
                "<p>[STUB - no model call] Original grounds of defence for Issue #" + issue.issueNo() + " to be written by the new-issue model.</p>",
                "",
                "<p>[STUB - no model call] Legal submissions for Issue #" + issue.issueNo() + " to be written by the new-issue model.</p>",
                List.of(),
                "Strength Note: stub mode, no model call was made.",
                List.of(), null, "stub", PROMPT_VERSION
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
