package com.noticedesk.api.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini Embedding Provider implementing 1536-dimensional vector generation via
 * Google Generative Language REST API using model gemini-embedding-001.
 */
@Slf4j
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    private static final int DEFAULT_DIMENSIONALITY = 1536;

    private final String apiKey;
    private final String model;
    private final int outputDimensionality;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiEmbeddingProvider(String apiKey) {
        this(apiKey, null, DEFAULT_DIMENSIONALITY);
    }

    public GeminiEmbeddingProvider(String apiKey, String model, int outputDimensionality) {
        this.apiKey = resolveApiKey(apiKey);
        String resolvedModel = (model != null && !model.isBlank()) ? model.trim() : System.getenv("EMBEDDING_MODEL");
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new IllegalArgumentException("Gemini embedding model is empty. Specify noticedesk.embedding.model or EMBEDDING_MODEL environment variable.");
        }
        this.model = resolvedModel.trim();
        this.outputDimensionality = outputDimensionality > 0 ? outputDimensionality : DEFAULT_DIMENSIONALITY;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private static String resolveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        env = System.getenv("GOOGLE_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "";
    }

    @Override
    public List<Double> embedQuery(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return fetchSingleEmbedding(text);
    }

    @Override
    public List<List<Double>> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<List<Double>> results = new ArrayList<>(texts.size());
        for (String t : texts) {
            results.add(embedQuery(t));
        }
        return results;
    }

    private List<Double> fetchSingleEmbedding(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("gemini_embedding_missing_api_key — falling back to zero vector");
            return createZeroVector(outputDimensionality);
        }

        String modelName = model.startsWith("models/") ? model : "models/" + model;
        String url = "https://generativelanguage.googleapis.com/v1beta/" + modelName + ":embedContent";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("content", Map.of("parts", List.of(Map.of("text", text))));
        bodyMap.put("output_dimensionality", outputDimensionality);

        int maxAttempts = 4;
        long backoffMs = 500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String requestBody = objectMapper.writeValueAsString(bodyMap);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode valuesNode = root.path("embedding").path("values");
                    if (valuesNode.isArray()) {
                        List<Double> vector = new ArrayList<>();
                        for (JsonNode val : valuesNode) {
                            vector.add(val.asDouble());
                        }
                        return vector;
                    }
                    log.error("gemini_embedding_invalid_response_format: {}", response.body());
                    return createZeroVector(outputDimensionality);
                }

                if ((response.statusCode() == 429 || response.statusCode() == 503) && attempt < maxAttempts) {
                    log.warn("gemini_embedding_rate_limited status={} attempt={} retrying after {}ms",
                            response.statusCode(), attempt, backoffMs);
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                    continue;
                }

                log.error("gemini_embedding_error status={} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Gemini Embedding API call failed status=" + response.statusCode() + " body=" + response.body());

            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("gemini_embedding_failed_all_attempts error={}", e.getMessage());
                    throw new RuntimeException("Gemini embedding failed after retries: " + e.getMessage(), e);
                }
                log.warn("gemini_embedding_attempt_failed attempt={} error={}", attempt, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
        return createZeroVector(outputDimensionality);
    }

    private static List<Double> createZeroVector(int dim) {
        List<Double> vec = new ArrayList<>(dim);
        for (int i = 0; i < dim; i++) {
            vec.add(0.0);
        }
        return vec;
    }
}
