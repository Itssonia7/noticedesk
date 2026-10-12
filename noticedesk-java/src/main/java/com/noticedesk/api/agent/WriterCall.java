package com.noticedesk.api.agent;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFatalException;
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
 *   <li>{@link LlmFatalException} (billing / auth error, live run aborted): thrown on to the caller;</li>
 *   <li>the outcome of every attempt is reported to {@link ApiUsageLogService#reportOutcome};</li>
 *   <li>max_tokens: retry once with the agent's higher retry cap;</li>
 *   <li>no text block or output that fails parsing/validation: retry once, telling the model the
 *       validation error; a second failure stops (the caller leaves the issue to the partner);</li>
 *   <li>no call (first attempt or retry) is made when the notice's {@link NoticeCallBudget} refuses it,
 *       including when the call's worst-case cost would take the notice over its cost cap.</li>
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
            int maxTokens = properties.getLlm().maxTokensForAgent(agentKey, raiseCap);
            String prompt = validationError != null ? userPrompt + retryInstruction(validationError) : userPrompt;
            // hard cap: worst case of exactly this call (prompt as sent, max_tokens as sent) must fit the budget;
            // system prompt + cached notice prefix form the cacheable prefix, the issue prompt the per-call part
            NoticeCallBudget.Prompt shape = NoticeCallBudget.Prompt.of(model, cacheablePrefix(systemPrompt, cachedUserPrefix), prompt);
            String refusal = budget != null ? budget.refusalReason(agentKey, shape, maxTokens) : null;
            if (refusal != null) {
                log.warn("{} not called (attempt={}): {}", label, attempt, refusal);
                return null;
            }

            LlmResponse response;
            try {
                response = cachedUserPrefix != null
                        ? llm.generateWithEffort(systemPrompt, cachedUserPrefix, prompt, model, maxTokens, effort, true)
                        : llm.generateWithEffort(systemPrompt, prompt, model, maxTokens, effort, true);
            } catch (RuntimeException e) {
                if (budget != null) budget.recordCall(agentKey, null, model);
                log.error("{} call failed attempt={} max_tokens={} error={}: {}", label, attempt, maxTokens,
                        e.getClass().getSimpleName(), e.getMessage());
                if (e instanceof LlmFatalException fatal) throw fatal;   // billing / auth / run aborted
                outcome(usage, agentKey, attempt, false, e.getClass().getSimpleName());
                return null;
            }
            if (budget != null) budget.recordCall(agentKey, response, model, shape);
            if (usage != null) {
                if (budget == null && response != null && usage.promptCache() != null) {
                    int cacheTokens = response.cacheWriteTokensOrZero() + response.cacheReadTokensOrZero();
                    if (cacheTokens > 0) usage.promptCache().recordUse(model, shape.prefixHash(), cacheTokens);
                }
                usage.logCall(response, model, agentKey, attempt, null, noticeId);
            }

            if (response == null) {
                log.warn("{} returned no response attempt={}", label, attempt);
                outcome(usage, agentKey, attempt, false, "no_response");
                return null;
            }
            if (response.refused()) {
                log.warn("{} refused stop_reason={} attempt={}; not retrying", label, response.stopReason(), attempt);
                outcome(usage, agentKey, attempt, false, LlmResponse.FAILURE_REFUSAL);
                return null;
            }
            if (response.truncated()) {
                outcome(usage, agentKey, attempt, false, LlmResponse.FAILURE_MAX_TOKENS);
                log.warn("{} truncated stop_reason=max_tokens attempt={} max_tokens={} output_tokens={}",
                        label, attempt, maxTokens, response.outputTokensOrZero());
                raiseCap = true;
                validationError = null;
                continue;
            }
            if (response.failed()) {
                outcome(usage, agentKey, attempt, false, response.failureReason());
                validationError = "the response contained no text output (" + response.failureReason() + ")";
                log.warn("{} unusable output attempt={} failure={} stop_reason={}",
                        label, attempt, response.failureReason(), response.stopReason());
                continue;
            }
            try {
                T parsed = parser.apply(response);
                if (parsed != null) {
                    outcome(usage, agentKey, attempt, true, null);
                    return parsed;
                }
                validationError = "the JSON object did not contain the required fields";
            } catch (IllegalArgumentException e) {
                validationError = e.getMessage();
            }
            logUnusable(label, attempt, response, validationError, properties);
            outcome(usage, agentKey, attempt, false, "invalid_output");
        }
        return null;
    }

    /** The text the provider caches for a writer call: the system prompt and (when sent) the notice prefix. */
    static String cacheablePrefix(String systemPrompt, String cachedUserPrefix) {
        String sys = systemPrompt != null ? systemPrompt : "";
        return cachedUserPrefix != null && !cachedUserPrefix.isBlank() ? sys + cachedUserPrefix : sys;
    }

    /** Characters sent as input: system prompt, cached user prefix and user prompt. */
    static long promptChars(String... parts) {
        long n = 0;
        for (String p : parts) if (p != null) n += p.length();
        return n;
    }

    private static void outcome(ApiUsageLogService usage, String agentKey, int attempt, boolean usable, String reason) {
        if (usage != null) usage.reportOutcome(agentKey, attempt, usable, reason);
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
