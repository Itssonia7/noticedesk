package com.noticedesk.api.service.llm;

public record LlmResponse(
        String content,
        String model,
        String providerName,
        Integer inputTokens,
        Integer outputTokens,
        String stopReason,
        Integer cacheCreationInputTokens,
        Integer cacheReadInputTokens
) {
    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens) {
        this(content, model, providerName, inputTokens, outputTokens, "end_turn", 0, 0);
    }

    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens, String stopReason) {
        this(content, model, providerName, inputTokens, outputTokens, stopReason, 0, 0);
    }
}
