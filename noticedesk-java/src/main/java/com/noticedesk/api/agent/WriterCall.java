package com.noticedesk.api.agent;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.function.Function;

/**
 * Attempt policy shared by the v7 Stage 3 writers (at most two calls):
 * <ul>
 *   <li>every call's usage is logged, including failed ones (they are billed);</li>
 *   <li>refusal: stop at once, no retry;</li>
 *   <li>max_tokens: retry once with the agent's higher retry cap;</li>
 *   <li>no text block or unparseable output: retry once (if {@code retryOnBadOutput}) with {@code retrySuffix}.</li>
 * </ul>
 */
@Slf4j
final class WriterCall {

    private WriterCall() {}

    static <T> T run(LlmProvider llm, ApiUsageLogService usage, AppProperties properties,
                     String agentKey, String model, String systemPrompt, String userPrompt, UUID noticeId,
                     boolean retryOnBadOutput, String retrySuffix, Function<LlmResponse, T> parser) {
        String effort = properties.getLlm().effortForAgent(agentKey);
        boolean raiseCap = false;
        boolean badOutput = false;

        for (int attempt = 1; attempt <= 2; attempt++) {
            int maxTokens = properties.getLlm().maxTokensForAgent(agentKey, raiseCap);
            String prompt = badOutput && retrySuffix != null ? userPrompt + retrySuffix : userPrompt;

            LlmResponse response = llm.generateWithEffort(systemPrompt, prompt, model, maxTokens, effort, true);
            if (usage != null) {
                usage.logCall(response, model, agentKey, attempt, null, noticeId);
            }

            if (response.refused()) {
                log.warn("{} refused stop_reason={} attempt={}; not retrying", agentKey, response.stopReason(), attempt);
                return null;
            }
            if (response.truncated()) {
                log.warn("{} truncated stop_reason=max_tokens attempt={} max_tokens={}", agentKey, attempt, maxTokens);
                raiseCap = true;
                badOutput = false;
                continue;
            }
            T parsed = response.failed() ? null : parser.apply(response);
            if (parsed != null) {
                return parsed;
            }
            log.warn("{} unusable output attempt={} failure={} stop_reason={}",
                    agentKey, attempt, response.failureReason(), response.stopReason());
            if (!retryOnBadOutput) {
                return null;
            }
            badOutput = true;
        }
        return null;
    }
}
