package com.noticedesk.api.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds, String workspaceId) {
        this.apiKey = apiKey;
        this.model = model;

        int timeoutMs = (int) (timeoutSeconds * 1000);
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .setConnectionRequestTimeout(30_000, TimeUnit.MILLISECONDS)
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
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", Math.min(maxOutputTokens, 12000));

            // System prompt with prompt caching
            ArrayNode systemArray = body.putArray("system");
            ObjectNode systemBlock = systemArray.addObject();
            systemBlock.put("type", "text");
            systemBlock.put("text", system);
            ObjectNode cacheControl = systemBlock.putObject("cache_control");
            cacheControl.put("type", "ephemeral");

            // User message
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", user);

            // Only include temperature for older models that accept it
            if (temperature > 0.0 && !modelDeprecatesTemperature(model)) {
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

            return new LlmResponse(content, model, "anthropic", inputTokens, outputTokens);

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
