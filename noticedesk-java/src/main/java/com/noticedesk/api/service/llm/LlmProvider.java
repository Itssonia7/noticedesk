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
     * This provider with the sampling temperature fixed for every call made through the returned instance (used by
     * calls whose answer must be the same from run to run, such as the shortlist title pass). Providers without a
     * per-call temperature, and models that reject one (thinking models), return themselves: the call is unchanged.
     */
    default LlmProvider withTemperature(double temperature) {
        return this;
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

    /**
     * As {@link #generateWithEffort}, with a user-message prefix shared by several calls (e.g. the notice
     * text sent once per issue). Providers with prompt caching mark the prefix cacheable; others send
     * {@code cachedUserPrefix + "\n\n" + userPrompt}.
     */
    default LlmResponse generateWithEffort(
            String systemPrompt,
            String cachedUserPrefix,
            String userPrompt,
            String model,
            int maxTokens,
            String effort,
            boolean cacheSystemPrompt) {
        String user = cachedUserPrefix == null || cachedUserPrefix.isBlank() ? userPrompt : cachedUserPrefix + "\n\n" + userPrompt;
        return generateWithEffort(systemPrompt, user, model, maxTokens, effort, cacheSystemPrompt);
    }
}
