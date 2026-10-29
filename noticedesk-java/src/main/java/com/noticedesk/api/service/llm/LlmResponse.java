package com.noticedesk.api.service.llm;

/**
 * Result of one model call. Usage fields are always filled from the provider's response (the call is
 * billed even when it fails), so callers must log usage before checking {@link #failed()}.
 *
 * @param failureReason null for a usable response; otherwise why the response must not be used
 *                      ({@code "max_tokens"}, {@code "refusal"}, {@code "no_text_block"}, ...)
 */
public record LlmResponse(
        String content,
        String model,
        String providerName,
        Integer inputTokens,
        Integer outputTokens,
        String stopReason,
        Integer cacheCreationInputTokens,
        Integer cacheReadInputTokens,
        String failureReason
) {
    public static final String FAILURE_MAX_TOKENS = "max_tokens";
    public static final String FAILURE_REFUSAL = "refusal";
    public static final String FAILURE_NO_TEXT = "no_text_block";

    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens) {
        this(content, model, providerName, inputTokens, outputTokens, "end_turn", 0, 0, null);
    }

    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens, String stopReason) {
        this(content, model, providerName, inputTokens, outputTokens, stopReason, 0, 0, null);
    }

    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens,
                       String stopReason, Integer cacheCreationInputTokens, Integer cacheReadInputTokens) {
        this(content, model, providerName, inputTokens, outputTokens, stopReason,
                cacheCreationInputTokens, cacheReadInputTokens, null);
    }

    /** True when the response is not usable (truncated, refused, or no text output). */
    public boolean failed() {
        return failureReason != null;
    }

    /** True when the output was cut off at max_tokens (a retry with a higher cap may succeed). */
    public boolean truncated() {
        return FAILURE_MAX_TOKENS.equals(failureReason) || "max_tokens".equalsIgnoreCase(stopReason);
    }

    /** True when the model declined the request; retrying the same prompt is pointless. */
    public boolean refused() {
        return FAILURE_REFUSAL.equals(failureReason) || "refusal".equalsIgnoreCase(stopReason);
    }

    public int inputTokensOrZero() { return inputTokens != null ? inputTokens : 0; }
    public int outputTokensOrZero() { return outputTokens != null ? outputTokens : 0; }
    public int cacheReadTokensOrZero() { return cacheReadInputTokens != null ? cacheReadInputTokens : 0; }
    public int cacheWriteTokensOrZero() { return cacheCreationInputTokens != null ? cacheCreationInputTokens : 0; }
}
