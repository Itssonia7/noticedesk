package com.noticedesk.api.service.llm;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import java.util.List;
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
        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                log.debug("gemini_generate_text_start model={} maxTokens={} attempt={}", model, maxOutputTokens, attempt);
                
                // Build the configuration
                GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                    .temperature((float) temperature)
                    .maxOutputTokens(maxOutputTokens);

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
                
                String text = response.text();
                log.debug("gemini_generate_text_success model={} outputLength={}", model, text != null ? text.length() : 0);
                
                return new LlmResponse(text, model, "gemini", null, null);
                
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
}
