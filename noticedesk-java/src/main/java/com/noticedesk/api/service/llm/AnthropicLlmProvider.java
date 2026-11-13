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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Cap used before max_tokens became configurable; kept for the legacy constructors. */
    private static final int LEGACY_MAX_TOKENS_CEILING = 16000;
    /**
     * Model name patterns known to reject the {@code temperature} field (HTTP 400 "temperature is deprecated for this
     * model"): the 4.x families, every 5-5 model (haiku / sonnet / opus and any other {@code -5-5} suffix).
     */
    private static final Pattern KNOWN_NO_TEMPERATURE_MODELS = Pattern.compile(
            "opus-4|sonnet-4|haiku-4|claude-haiku-5-5|claude-sonnet-5-5|claude-opus-5-5|-5-5(?![0-9])");
    /**
     * Models that rejected {@code temperature} at run time (HTTP 400 naming the field as deprecated / not supported),
     * remembered for the life of the process so no later call of any instance sends it to them again.
     */
    private static final Set<String> LEARNED_NO_TEMPERATURE_MODELS = ConcurrentHashMap.newKeySet();

    private final String model;
    private final RestClient restClient;
    private final Set<String> effortModels;
    private final int maxTokensCeiling;
    /** Temperature sent with every call of this instance (even 0.0); null = the legacy rule (only when > 0). */
    private final Double fixedTemperature;

    /** A view of {@code base} that sends {@code temperature} (0.0 included) to models that accept one. */
    private AnthropicLlmProvider(AnthropicLlmProvider base, double temperature) {
        this.model = base.model;
        this.restClient = base.restClient;
        this.effortModels = base.effortModels;
        this.maxTokensCeiling = base.maxTokensCeiling;
        this.fixedTemperature = temperature;
    }

    /**
     * {@inheritDoc} Thinking (effort) models reject sampling parameters and keep their effort setting; for every other
     * model the temperature is sent on each call of the returned instance, 0.0 included.
     */
    @Override
    public LlmProvider withTemperature(double temperature) {
        return new AnthropicLlmProvider(this, temperature);
    }

    /** The temperature fixed by {@link #withTemperature}, or null. */
    Double fixedTemperature() {
        return fixedTemperature;
    }

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
        this.fixedTemperature = null;

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
        return call(systemPrompt, null, userPrompt, targetModel, maxOutputTokens, 0.0, effort, cacheSystemPrompt);
    }

    @Override
    public LlmResponse generateWithEffort(
            String systemPrompt,
            String cachedUserPrefix,
            String userPrompt,
            String targetModel,
            int maxOutputTokens,
            String effort,
            boolean cacheSystemPrompt) {
        return call(systemPrompt, cachedUserPrefix, userPrompt, targetModel, maxOutputTokens, 0.0, effort, cacheSystemPrompt);
    }

    private LlmResponse call(String systemPrompt, String userPrompt, String targetModel, int maxOutputTokens,
                             double temperature, String effort, boolean cacheSystemPrompt) {
        return call(systemPrompt, null, userPrompt, targetModel, maxOutputTokens, temperature, effort, cacheSystemPrompt);
    }

    private LlmResponse call(String systemPrompt, String cachedUserPrefix, String userPrompt, String targetModel,
                             int maxOutputTokens, double temperature, String effort, boolean cacheSystemPrompt) {
        String activeModel = (targetModel != null && !targetModel.isBlank()) ? targetModel.trim() : this.model;
        ObjectNode body = buildRequestBody(systemPrompt, cachedUserPrefix, userPrompt, activeModel, maxOutputTokens,
                temperature, effort, cacheSystemPrompt);
        try {
            return post(body, activeModel);
        } catch (LlmException e) {
            // a model that rejects the temperature field: retry the same request once without it, and never send it
            // to that model again in this process (the known list cannot name every future model)
            if (body.has("temperature") && isTemperatureRejection(e)) {
                LEARNED_NO_TEMPERATURE_MODELS.add(activeModel.toLowerCase(Locale.ROOT));
                log.warn("anthropic_temperature_rejected model={}: retrying once without the temperature field; "
                        + "no later call sends it to this model", activeModel);
                body.remove("temperature");
                return post(body, activeModel);
            }
            throw e;
        }
    }

    /**
     * True for an HTTP 400 whose error message names the temperature field as deprecated / not supported for the
     * model (transient and fatal errors are never read this way).
     */
    static boolean isTemperatureRejection(LlmException e) {
        if (e instanceof LlmTransientException || e instanceof LlmFatalException || e.getMessage() == null) return false;
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("http 400") && m.contains("temperature") && (m.contains("deprecated") || m.contains("not supported"));
    }

    /** Models learnt to reject temperature in this process (lower case); for tests and diagnostics. */
    static Set<String> learnedNoTemperatureModels() {
        return Set.copyOf(LEARNED_NO_TEMPERATURE_MODELS);
    }

    /** Forgets the models learnt to reject temperature (tests). */
    static void forgetLearnedNoTemperatureModels() {
        LEARNED_NO_TEMPERATURE_MODELS.clear();
    }

    /** One POST of {@code body}; every failure is an {@link LlmException} (transient / fatal / plain). */
    private LlmResponse post(ObjectNode body, String activeModel) {
        try {
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
                        // billing / auth errors: no retry can succeed
                        LlmFatalException fatal = LlmFatalException.forHttpError(code, errBody);
                        if (fatal != null) throw fatal;
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
     * those models reject. Other models keep the legacy shape (temperature when > 0 and accepted); a model that
     * {@link #modelDeprecatesTemperature rejects the field} (known family, or learnt from an HTTP 400 in this process)
     * never gets one.
     */
    ObjectNode buildRequestBody(String systemPrompt, String userPrompt, String activeModel, int maxOutputTokens,
                                double temperature, String effort, boolean cacheSystemPrompt) {
        return buildRequestBody(systemPrompt, null, userPrompt, activeModel, maxOutputTokens, temperature, effort, cacheSystemPrompt);
    }

    /**
     * {@code cachedUserPrefix} (may be null) is sent as the first user content block with
     * {@code cache_control}, so calls that share it (one per issue of the same notice) read it from cache.
     */
    ObjectNode buildRequestBody(String systemPrompt, String cachedUserPrefix, String userPrompt, String activeModel,
                                int maxOutputTokens, double temperature, String effort, boolean cacheSystemPrompt) {
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
        if (cachedUserPrefix != null && !cachedUserPrefix.isBlank()) {
            ArrayNode content = userMsg.putArray("content");
            ObjectNode prefixBlock = content.addObject();
            prefixBlock.put("type", "text");
            prefixBlock.put("text", cachedUserPrefix);
            prefixBlock.putObject("cache_control").put("type", "ephemeral");
            ObjectNode rest = content.addObject();
            rest.put("type", "text");
            rest.put("text", userPrompt);
        } else {
            userMsg.put("content", userPrompt);
        }

        if (isEffortModel(activeModel)) {
            if (effort != null && !effort.isBlank()) {
                body.putObject("output_config").put("effort", effort.trim());
            } else if (effort != null) {   // null = legacy call path that has no effort setting
                log.warn("anthropic_effort_unset model={}: no output_config.effort sent, model default applies", activeModel);
            }
        } else if (fixedTemperature != null && !modelDeprecatesTemperature(activeModel)) {
            body.put("temperature", fixedTemperature);   // withTemperature: sent on every call, 0.0 included
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

    /**
     * True when {@code temperature} must not be sent to the model: a known family ({@link #KNOWN_NO_TEMPERATURE_MODELS})
     * or a model that rejected the field earlier in this process.
     */
    static boolean modelDeprecatesTemperature(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.trim().toLowerCase(Locale.ROOT);
        return KNOWN_NO_TEMPERATURE_MODELS.matcher(lower).find() || LEARNED_NO_TEMPERATURE_MODELS.contains(lower);
    }
}
