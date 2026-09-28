package com.noticedesk.api.service.llm;

public record LlmResponse(
        String content,
        String model,
        String providerName,
        Integer inputTokens,
        Integer outputTokens,
        String stopReason
) {
    public LlmResponse(String content, String model, String providerName, Integer inputTokens, Integer outputTokens) {
        this(content, model, providerName, inputTokens, outputTokens, "end_turn");
    }
}
