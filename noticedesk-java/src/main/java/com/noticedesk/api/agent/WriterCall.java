package com.noticedesk.api.agent;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.function.Function;

/**
 * Attempt policy shared by the v7 Stage 3 writers (at most two calls per issue):
 * <ul>
 *   <li>every call's usage is logged, including failed ones (they are billed);</li>
 *   <li>refusal or an exception (timeout, HTTP error): stop at once, no retry;</li>
 *   <li>max_tokens: retry once with the agent's higher retry cap;</li>
 *   <li>no text block or output that fails parsing/validation: retry once, telling the model the
 *       validation error; a second failure stops (the caller leaves the issue to the partner);</li>
 *   <li>no call is made once the notice's {@link NoticeCallBudget} refuses it.</li>
 * </ul>
 * The parser throws {@link IllegalArgumentException} with a short validation message on bad output.
 */
@Slf4j
final class WriterCall {

    private static final int RAW_SAMPLE_CHARS = 400;

    private WriterCall() {}

    static <T> T run(LlmProvider llm, ApiUsageLogService usage, AppProperties properties,
                     String agentKey, String label, String model, String systemPrompt, String cachedUserPrefix,
                     String userPrompt, UUID noticeId, NoticeCallBudget budget, Function<LlmResponse, T> parser) {
        String effort = properties.getLlm().effortForAgent(agentKey);
        boolean raiseCap = false;
        String validationError = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            String refusal = budget != null ? budget.refusalReason(agentKey) : null;
            if (refusal != null) {
                log.warn("{} not called (attempt={}): {}", label, attempt, refusal);
                return null;
            }
            int maxTokens = properties.getLlm().maxTokensForAgent(agentKey, raiseCap);
            String prompt = validationError != null ? userPrompt + retryInstruction(validationError) : userPrompt;

            LlmResponse response;
            try {
                response = cachedUserPrefix != null
                        ? llm.generateWithEffort(systemPrompt, cachedUserPrefix, prompt, model, maxTokens, effort, true)
                        : llm.generateWithEffort(systemPrompt, prompt, model, maxTokens, effort, true);
            } catch (RuntimeException e) {
                if (budget != null) budget.recordCall(agentKey, null, model);
                log.error("{} call failed attempt={} max_tokens={} error={}: {}", label, attempt, maxTokens,
                        e.getClass().getSimpleName(), e.getMessage());
                return null;
            }
            if (budget != null) budget.recordCall(agentKey, response, model);
            if (usage != null) {
                usage.logCall(response, model, agentKey, attempt, null, noticeId);
            }

            if (response == null) {
                log.warn("{} returned no response attempt={}", label, attempt);
                return null;
            }
            if (response.refused()) {
                log.warn("{} refused stop_reason={} attempt={}; not retrying", label, response.stopReason(), attempt);
                return null;
            }
            if (response.truncated()) {
                log.warn("{} truncated stop_reason=max_tokens attempt={} max_tokens={} output_tokens={}",
                        label, attempt, maxTokens, response.outputTokensOrZero());
                raiseCap = true;
                validationError = null;
                continue;
            }
            if (response.failed()) {
                validationError = "the response contained no text output (" + response.failureReason() + ")";
                log.warn("{} unusable output attempt={} failure={} stop_reason={}",
                        label, attempt, response.failureReason(), response.stopReason());
                continue;
            }
            try {
                T parsed = parser.apply(response);
                if (parsed != null) return parsed;
                validationError = "the JSON object did not contain the required fields";
            } catch (IllegalArgumentException e) {
                validationError = e.getMessage();
            }
            logUnusable(label, attempt, response, validationError, properties);
        }
        return null;
    }

    private static String retryInstruction(String validationError) {
        return "\n\nYOUR PREVIOUS RESPONSE COULD NOT BE USED: " + validationError
                + ". Reply again with exactly ONE JSON object in the required format: no markdown fences, no text"
                + " before or after it. Inside HTML strings never use the double-quote character; use single quotes"
                + " or typographic quotes instead.";
    }

    /** Shape of an unusable response; content only when {@code llm.debug-log-raw-output} is on (test bench). */
    private static void logUnusable(String label, int attempt, LlmResponse response, String error, AppProperties properties) {
        String raw = response.content() != null ? response.content() : "";
        String trimmed = raw.strip();
        log.warn("{} output failed validation attempt={} error=\"{}\" stop_reason={} output_tokens={} length={} starts_with='{}' has_fence={} brace_at={}",
                label, attempt, error, response.stopReason(), response.outputTokensOrZero(), raw.length(),
                trimmed.isEmpty() ? "" : trimmed.substring(0, 1), raw.contains("```"), raw.indexOf('{'));
        if (properties.getLlm().isDebugLogRawOutput()) {
            String sample = raw.length() > RAW_SAMPLE_CHARS ? raw.substring(0, RAW_SAMPLE_CHARS) + "..." : raw;
            log.warn("{} raw output sample (debug-log-raw-output): {}", label, sample.replace("\n", "\\n"));
        }
    }
}
