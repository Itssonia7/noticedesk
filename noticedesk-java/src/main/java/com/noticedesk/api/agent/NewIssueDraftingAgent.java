package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.NoticeCallBudget;
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
            String promptVersion,
            List<AssumedFact> assumedFacts
    ) {
        public NewIssueDraftingResult {
            assumedFacts = assumedFacts != null ? List.copyOf(assumedFacts) : List.of();
        }

        public NewIssueDraftingResult(int issueNo, String section04Html, String section05Html, String section06Html,
                                      List<RawAiCitation> citations, String strengthNote) {
            this(issueNo, section04Html, section05Html, section06Html, citations, strengthNote,
                    List.of(), null, null, PROMPT_VERSION);
        }

        /** No assumed facts. */
        public NewIssueDraftingResult(int issueNo, String section04Html, String section05Html, String section06Html,
                                      List<RawAiCitation> citations, String strengthNote, List<String> documents,
                                      String summaryLine, String model, String promptVersion) {
            this(issueNo, section04Html, section05Html, section06Html, citations, strengthNote, documents, summaryLine,
                    model, promptVersion, List.of());
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

    public NewIssueDraftingResult generateNewIssueDraft(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            String fullOcrText,
            String stageName,
            UUID noticeId,
            boolean highStakes
    ) {
        return generateNewIssueDraft(issue, noticeInfo, fullOcrText, stageName, noticeId, highStakes, null);
    }

    /**
     * @param highStakes when true the high-stakes model ({@code LLM_MODEL_HIGH_STAKES}) writes the issue
     * @param budget     per-notice call/cost limits (null = none)
     * @return the parsed output, or {@code null} when the model call failed, returned unusable output twice,
     *         or the budget refused the call
     */
    public NewIssueDraftingResult generateNewIssueDraft(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            String fullOcrText,
            String stageName,
            UUID noticeId,
            boolean highStakes,
            NoticeCallBudget budget
    ) {
        String agentKey = highStakes ? "high_stakes" : "new_issue";
        // high-stakes issues are written with this agent's prompt by the high-stakes model: log them as such
        String label = (highStakes ? "HighStakesDraftingAgent" : "NewIssueDraftingAgent") + " issue #" + issue.issueNo();
        // the agent's own provider when set (LLM_PROVIDER_NEW_ISSUE / LLM_PROVIDER_HIGH_STAKES), else the primary
        String provider = properties.getLlm() != null ? properties.getLlm().providerForAgent(agentKey) : null;
        String model = properties.getLlm() != null
                ? properties.writerModel(agentKey, highStakes ? properties.getLlm().getModelHighStakes() : properties.getLlm().getModelNewIssue())
                : null;
        if (model == null || model.isBlank()) {
            String env = highStakes ? "LLM_MODEL_HIGH_STAKES" : "LLM_MODEL_NEW_ISSUE";
            throw new IllegalStateException(env + " is not configured. Set environment variable " + env + ".");
        }

        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("{} running in stub mode", label);
            return stubResult(issue);
        }

        // The notice context is identical for every issue of the notice: sent first as a cacheable block.
        Map<String, Object> noticeContext = new LinkedHashMap<>();
        noticeContext.put("notice", noticeInfo);
        noticeContext.put("stage", stageName);
        noticeContext.put("full_ocr_text", fullOcrText);
        Map<String, Object> issuePayload = new LinkedHashMap<>();
        issuePayload.put("issue", issue);

        String systemPrompt = loadPrompt();
        String noticePrompt;
        String issuePrompt;
        try {
            noticePrompt = "NOTICE CONTEXT (JSON):\n" + objectMapper.writeValueAsString(noticeContext);
            issuePrompt = "ISSUE TO ANSWER (JSON):\n" + objectMapper.writeValueAsString(issuePayload);
        } catch (Exception e) {
            noticePrompt = "NOTICE CONTEXT:\n" + noticeContext;
            issuePrompt = "ISSUE TO ANSWER:\n" + issuePayload;
        }

        try {
            LlmProvider llmProvider = llmFactory.getLlmForAgent(agentKey);
            NewIssueDraftingResult result = WriterCall.run(llmProvider, apiUsageLogService, properties, agentKey, label, model,
                    systemPrompt, noticePrompt, issuePrompt, noticeId, budget,
                    response -> parseOrThrow(response.content(), issue.issueNo(),
                            response.model() != null ? response.model() : model));
            if (result == null) {
                log.warn("{} produced no usable output", label);
            }
            return result;
        } catch (LlmFatalException e) {
            throw e;   // billing / auth error or live run aborted: never a silent fallback
        } catch (Exception e) {
            log.error("{} execution failed: {}", label, e.getMessage());
            return null;
        }
    }

    NewIssueDraftingResult parseAndSanitize(String json, int issueNo, String model) {
        try {
            return parseOrThrow(json, issueNo, model);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse new-issue writer JSON: {}", e.getMessage());
            return null;
        }
    }

    /** @throws IllegalArgumentException (short message, no content) when the output is not usable */
    NewIssueDraftingResult parseOrThrow(String json, int issueNo, String model) {
        Map<String, Object> map = AiOutputParser.readJsonObject(objectMapper, json);

        String sec04 = HtmlSanitizer.sanitize(Objects.toString(map.get("section04_html"), ""));
        String sec05 = HtmlSanitizer.sanitize(Objects.toString(map.get("section05_html"), ""));
        String sec06 = HtmlSanitizer.sanitize(Objects.toString(map.get("section06_html"), ""));
        if (sec04.isBlank() && sec06.isBlank()) {
            throw new IllegalArgumentException("section04_html and section06_html are both missing or empty (keys present: "
                    + new TreeSet<>(map.keySet()) + ")");
        }

        return new NewIssueDraftingResult(issueNo, sec04, sec05, sec06,
                AiOutputParser.citations(map),
                AiOutputParser.str(map.get("strength_note")),
                AiOutputParser.stringList(map, "documents"),
                AiOutputParser.str(map.get("summary_line")),
                model, PROMPT_VERSION,
                AiOutputParser.assumedFacts(map));
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
