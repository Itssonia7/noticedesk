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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Cap used before max_tokens became configurable; kept for the legacy constructors. */
    private static final int LEGACY_MAX_TOKENS_CEILING = 16000;

    private final String model;
    private final RestClient restClient;
    private final Set<String> effortModels;
    private final int maxTokensCeiling;

    public AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds, String workspaceId,
                                Collection<String> effortModels, int maxTokensCeiling) {
        this(apiKey, model, timeoutSeconds, workspaceId, effortModels, maxTokensCeiling, API_URL);
    }

    /** {@code apiUrl} is overridable for tests against a local mock server. */
    AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds, String workspaceId,
                         Collection<String> effortModels, int maxTokensCeiling, String apiUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("anthropic api_key is missing or empty");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("anthropic model is missing or empty");
        }
        this.model = model.trim();
        this.effortModels = normaliseModels(effortModels);
        this.maxTokensCeiling = maxTokensCeiling > 0 ? maxTokensCeiling : LEGACY_MAX_TOKENS_CEILING;

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
                .baseUrl(apiUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.defaultHeader("anthropic-workspace-id", workspaceId);
        }

        this.restClient = builder.build();
    }

    public AnthropicLlmProvider(String apiKey, String model, double timeoutSeconds, String workspaceId) {
        this(apiKey, model, timeoutSeconds, workspaceId, List.of(), LEGACY_MAX_TOKENS_CEILING);
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
        return call(systemPrompt, userPrompt, targetModel, maxOutputTokens, temperature, null, cacheSystemPrompt);
    }

    @Override
    public LlmResponse generateWithEffort(
            String systemPrompt,
            String userPrompt,
            String targetModel,
            int maxOutputTokens,
            String effort,
            boolean cacheSystemPrompt) {
        return call(systemPrompt, userPrompt, targetModel, maxOutputTokens, 0.0, effort, cacheSystemPrompt);
    }

    private LlmResponse call(String systemPrompt, String userPrompt, String targetModel, int maxOutputTokens,
                             double temperature, String effort, boolean cacheSystemPrompt) {
        try {
            String activeModel = (targetModel != null && !targetModel.isBlank()) ? targetModel.trim() : this.model;
            ObjectNode body = buildRequestBody(systemPrompt, userPrompt, activeModel, maxOutputTokens,
                    temperature, effort, cacheSystemPrompt);

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

            return parseResponse(MAPPER.readTree(responseBody), activeModel);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmTransientException("anthropic call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the Messages API request body. Models in {@code effortModels} always think: they get
     * {@code output_config.effort} (when configured) and never thinking/temperature/top_p/top_k, which
     * those models reject. Other models keep the legacy shape (temperature when > 0 and accepted).
     */
    ObjectNode buildRequestBody(String systemPrompt, String userPrompt, String activeModel, int maxOutputTokens,
                                double temperature, String effort, boolean cacheSystemPrompt) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", activeModel);
        body.put("max_tokens", Math.min(maxOutputTokens, maxTokensCeiling));

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

        if (isEffortModel(activeModel)) {
            if (effort != null && !effort.isBlank()) {
                body.putObject("output_config").put("effort", effort.trim());
            } else if (effort != null) {   // null = legacy call path that has no effort setting
                log.warn("anthropic_effort_unset model={}: no output_config.effort sent, model default applies", activeModel);
            }
        } else if (temperature > 0.0 && !modelDeprecatesTemperature(activeModel)) {
            body.put("temperature", temperature);
        }
        return body;
    }

    /**
     * Reads a Messages API response. The text is the concatenation of every {@code text} block;
     * thinking / redacted_thinking and other block types are skipped. Usage is always returned
     * (the call is billed either way); the response is marked failed when it was truncated, refused,
     * or carries no text block.
     */
    static LlmResponse parseResponse(JsonNode json, String requestedModel) {
        StringBuilder text = new StringBuilder();
        boolean hasTextBlock = false;
        JsonNode contentArray = json.path("content");
        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText())) {
                    hasTextBlock = true;
                    text.append(block.path("text").asText(""));
                }
            }
        }

        JsonNode usage = json.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);   // includes thinking tokens
        int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(0);
        int cacheReadTokens = usage.path("cache_read_input_tokens").asInt(0);
        String stopReason = json.path("stop_reason").asText("unknown");
        String model = json.path("model").asText("");
        if (model.isBlank()) model = requestedModel;

        String failure = null;
        if ("max_tokens".equals(stopReason)) {
            failure = LlmResponse.FAILURE_MAX_TOKENS;
        } else if ("refusal".equals(stopReason)) {
            failure = LlmResponse.FAILURE_REFUSAL;
        } else if (!hasTextBlock || text.toString().isBlank()) {
            failure = LlmResponse.FAILURE_NO_TEXT;
        }
        if (failure != null) {
            // never log content: it can quote the notice
            JsonNode details = json.path("stop_details");
            String category = details.path("category").asText(details.path("type").asText("none"));
            log.warn("anthropic_response_unusable model={} failure={} stop_reason={} stop_details_category={} output_tokens={}",
                    model, failure, stopReason, category, outputTokens);
        }

        // failed responses carry "" (never partial text) so legacy callers that strip() do not NPE
        return new LlmResponse(failure == null ? text.toString() : "", model, "anthropic",
                inputTokens, outputTokens, stopReason, cacheCreationTokens, cacheReadTokens, failure);
    }

    boolean isEffortModel(String modelName) {
        return modelName != null && effortModels.contains(modelName.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> normaliseModels(Collection<String> models) {
        Set<String> out = new HashSet<>();
        if (models != null) {
            for (String m : models) {
                if (m != null && !m.isBlank()) out.add(m.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private boolean modelDeprecatesTemperature(String modelName) {
        String lower = modelName.toLowerCase();
        return lower.contains("opus-4") || lower.contains("sonnet-4") || lower.contains("haiku-4");
    }
}
