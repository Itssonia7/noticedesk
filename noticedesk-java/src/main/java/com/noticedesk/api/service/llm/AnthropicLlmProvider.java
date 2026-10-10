package com.noticedesk.api.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final RestClient restClient;

    public AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds, String workspaceId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("anthropic api_key is missing or empty");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("anthropic model is missing or empty");
        }
        this.model = model.trim();

        int timeoutMs = (int) (timeoutSeconds * 1000);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(API_URL)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.defaultHeader("anthropic-workspace-id", workspaceId);
        }

        this.restClient = builder.build();
    }

    public AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds) {
        this(apiKey, model, timeoutSeconds, null);
    }

    @Override
    public String getName() { return "anthropic"; }

    @Override
    public String getModel() { return model; }

    @Override
    public LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature) {
        return generateWithPromptCaching(system, user, this.model, maxOutputTokens, temperature, true);
    }

    @Override
    public LlmResponse generateWithPromptCaching(
            String systemPrompt,
            String userPrompt,
            String targetModel,
            int maxOutputTokens,
            double temperature,
            boolean cacheSystemPrompt) {
        try {
            String activeModel = (targetModel != null && !targetModel.isBlank()) ? targetModel.trim() : this.model;

            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", activeModel);
            body.put("max_tokens", Math.min(maxOutputTokens, 16000));

            // System prompt with optional prompt caching
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ArrayNode systemArray = body.putArray("system");
                ObjectNode systemBlock = systemArray.addObject();
                systemBlock.put("type", "text");
                systemBlock.put("text", systemPrompt);
                if (cacheSystemPrompt) {
                    ObjectNode cacheControl = systemBlock.putObject("cache_control");
                    cacheControl.put("type", "ephemeral");
                }
            }

            // User message
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            if (temperature > 0.0 && !modelDeprecatesTemperature(activeModel)) {
                body.put("temperature", temperature);
            }

            String responseBody = restClient.post()
                    .uri("")
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        String errBody = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.error("anthropic error body: {}", errBody);
                        if (code == 429 || code >= 500) {
                            throw new LlmTransientException("anthropic transient error: HTTP " + code + " " + errBody);
                        }
                        throw new LlmException("anthropic error: HTTP " + code + " - " + errBody);
                    })
                    .body(String.class);

            JsonNode json = MAPPER.readTree(responseBody);
            String content = json.path("content").get(0).path("text").asText();
            int inputTokens = json.path("usage").path("input_tokens").asInt(0);
            int outputTokens = json.path("usage").path("output_tokens").asInt(0);
            int cacheCreationTokens = json.path("usage").path("cache_creation_input_tokens").asInt(0);
            int cacheReadTokens = json.path("usage").path("cache_read_input_tokens").asInt(0);
            String stopReason = json.path("stop_reason").asText("unknown");

            return new LlmResponse(content, activeModel, "anthropic", inputTokens, outputTokens, stopReason, cacheCreationTokens, cacheReadTokens);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmTransientException("anthropic call failed: " + e.getMessage(), e);
        }
    }

    private boolean modelDeprecatesTemperature(String modelName) {
        String lower = modelName.toLowerCase();
        return lower.contains("opus-4") || lower.contains("sonnet-4") || lower.contains("haiku-4");
    }
}
