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

import java.util.concurrent.TimeUnit;

@Slf4j
public class OpenAiLlmProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final RestClient restClient;

    public OpenAiLlmProvider(String apiKey, String model, double timeoutSeconds, String baseUrl) {
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

        String resolvedBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(resolvedBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String getName() { return "openai"; }

    @Override
    public String getModel() { return model; }

    @Override
    public LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxOutputTokens);
            body.put("temperature", temperature);

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);

            String responseBody = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(MAPPER.writeValueAsString(body))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        if (code == 429 || code >= 500) {
                            throw new LlmTransientException("openai transient error: HTTP " + code);
                        }
                        throw new LlmException("openai error: HTTP " + code);
                    })
                    .body(String.class);

            JsonNode json = MAPPER.readTree(responseBody);
            String content = json.path("choices").get(0).path("message").path("content").asText();
            int inputTokens = json.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = json.path("usage").path("completion_tokens").asInt(0);
            String stopReason = json.path("choices").get(0).path("finish_reason").asText("unknown");

            return new LlmResponse(content, model, "openai", inputTokens, outputTokens, stopReason);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmTransientException("openai call failed: " + e.getMessage(), e);
        }
    }
}
