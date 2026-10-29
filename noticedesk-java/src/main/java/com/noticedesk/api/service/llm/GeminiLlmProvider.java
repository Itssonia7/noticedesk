package com.noticedesk.api.service.llm;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeminiLlmProvider implements LlmProvider {

    private final Client client;
    private final String apiKey;
    private final String model;
    private final double timeoutSeconds;

    public GeminiLlmProvider(String apiKey, String model, double timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        
        if (apiKey != null && !apiKey.isBlank()) {
            System.setProperty("GEMINI_API_KEY", apiKey);
            System.setProperty("GOOGLE_API_KEY", apiKey);
            this.client = Client.builder().apiKey(apiKey).build();
        } else {
            this.client = new Client();
        }
    }

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public String getModel() {
        return model;
    }

    private Content createTextContent(String text) {
        return Content.builder()
            .parts(List.of(Part.builder().text(text != null ? text : "").build()))
            .build();
    }

    @Override
    public LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature) {
        return call(system, user, maxOutputTokens, (float) temperature);
    }

    /**
     * Writer entry point. The provider has no effort control (the thinking config of this SDK takes no budget), so
     * {@code effort} is not sent; there is no cache_control either. No temperature is sent; max_tokens is sent as
     * the max output tokens. Stop reason and token counts come back in the {@link LlmResponse}.
     */
    @Override
    public LlmResponse generateWithEffort(String systemPrompt, String userPrompt, String model, int maxTokens, String effort,
                                          boolean cacheSystemPrompt) {
        if (model != null && !model.isBlank() && !model.equals(this.model)) {
            log.warn("gemini_model_mismatch requested={} provider={}; the provider's model is used", model, this.model);
        }
        return call(systemPrompt, userPrompt, maxTokens, null);
    }

    @Override
    public LlmResponse generateWithPromptCaching(String systemPrompt, String userPrompt, String model, int maxTokens,
                                                 double temperature, boolean cacheSystemPrompt) {
        return call(systemPrompt, userPrompt, maxTokens, (float) temperature);
    }

    private LlmResponse call(String system, String user, int maxOutputTokens, Float temperature) {
        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                log.debug("gemini_generate_text_start model={} maxTokens={} attempt={}", model, maxOutputTokens, attempt);

                // Build the configuration
                GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                    .maxOutputTokens(maxOutputTokens);
                if (temperature != null) {
                    configBuilder.temperature(temperature);
                }

                if (system != null && !system.isBlank()) {
                    configBuilder.systemInstruction(createTextContent(system));
                }

                GenerateContentConfig config = configBuilder.build();
                Content userContent = createTextContent(user);

                // Generate content
                GenerateContentResponse response = client.models.generateContent(
                    this.model,
                    userContent,
                    config
                );

                LlmResponse out = toLlmResponse(response);
                log.debug("gemini_generate_text_success model={} outputLength={} finish={}", model,
                        out.content() != null ? out.content().length() : 0, out.stopReason());
                return out;

            } catch (Exception e) {
                lastException = e;
                log.warn("gemini_api_attempt_failed attempt={} error={}", attempt, e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                boolean isTransient = msg.contains("429") || msg.contains("503") || msg.contains("500") || msg.contains("502") || msg.contains("timeout") || msg.contains("quota exceeded") || msg.contains("resource_exhausted");

                if (isTransient && attempt < maxRetries) {
                    try {
                        long sleepMs = 6000L * attempt; // Sleep 6s, 12s on retries
                        log.info("gemini_rate_limit_backoff sleeping {}ms before retry...", sleepMs);
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (!isTransient) {
                    throw new LlmException("Gemini API error: " + e.getMessage(), e);
                }
            }
        }

        String msg = lastException != null ? lastException.getMessage() : "Unknown error";
        throw new LlmTransientException("Gemini API transient error after retries: " + msg, lastException);
    }

    private LlmResponse toLlmResponse(GenerateContentResponse response) {
        String text = null;
        try {
            text = response.text();
        } catch (RuntimeException e) {
            log.warn("gemini_response_without_text model={} error={}", model, e.getMessage());
        }
        String finish = response.candidates().flatMap(c -> c.isEmpty() ? Optional.empty() : c.get(0).finishReason()).orElse(null);
        Optional<GenerateContentResponseUsageMetadata> usage = response.usageMetadata();
        return response(text, finish,
                usage.flatMap(GenerateContentResponseUsageMetadata::promptTokenCount).orElse(null),
                usage.flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount).orElse(null),
                usage.flatMap(GenerateContentResponseUsageMetadata::thoughtsTokenCount).orElse(null),
                model);
    }

    /**
     * The {@link LlmResponse} of one call from its parts: finish reason {@code STOP} is {@code end_turn},
     * {@code MAX_TOKENS} a {@code max_tokens} failure, a safety / blocklist / prohibited-content / recitation stop a
     * {@code refusal}; no text is {@code no_text_block}. Output tokens are the answer plus the thinking tokens (both
     * billed as output). Input tokens are the prompt tokens; no cache tokens are reported.
     */
    static LlmResponse response(String text, String finishReason, Integer promptTokens, Integer candidateTokens,
                                Integer thoughtTokens, String model) {
        String finish = finishReason != null ? finishReason.trim().toUpperCase(Locale.ROOT) : "";
        String stopReason;
        String failure = null;
        switch (finish) {
            case "", "STOP", "FINISH_REASON_UNSPECIFIED" -> stopReason = "end_turn";
            case "MAX_TOKENS" -> {
                stopReason = "max_tokens";
                failure = LlmResponse.FAILURE_MAX_TOKENS;
            }
            case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION", "IMAGE_SAFETY" -> {
                stopReason = "refusal";
                failure = LlmResponse.FAILURE_REFUSAL;
            }
            default -> stopReason = finish.toLowerCase(Locale.ROOT);
        }
        if (failure == null && (text == null || text.isBlank())) failure = LlmResponse.FAILURE_NO_TEXT;
        Integer output = candidateTokens == null && thoughtTokens == null ? null
                : (candidateTokens != null ? candidateTokens : 0) + (thoughtTokens != null ? thoughtTokens : 0);
        return new LlmResponse(text, model, "gemini", promptTokens, output, stopReason, 0, 0, failure);
    }
}
