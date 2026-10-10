package com.noticedesk.api.service.llm;

public interface LlmProvider {
    String getName();
    String getModel();
    LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature);

    default LlmResponse generateText(String system, String user) {
        return generateText(system, user, 4096, 0.0);
    }

    default LlmResponse generateWithPromptCaching(
            String systemPrompt,
            String userPrompt,
            String model,
            int maxTokens,
            double temperature,
            boolean cacheSystemPrompt) {
        return generateText(systemPrompt, userPrompt, maxTokens, temperature);
    }

    /**
     * Call used by the v7 agents: no sampling parameters, thinking depth set by {@code effort}
     * ({@code low|medium|high|xhigh|max}). Providers without an effort control ignore it.
     */
    default LlmResponse generateWithEffort(
            String systemPrompt,
            String userPrompt,
            String model,
            int maxTokens,
            String effort,
            boolean cacheSystemPrompt) {
        return generateWithPromptCaching(systemPrompt, userPrompt, model, maxTokens, 0.0, cacheSystemPrompt);
    }
}
