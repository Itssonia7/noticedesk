package com.noticedesk.api.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** No live calls: fixture JSON shaped like Messages API responses, served by a local HTTP server. */
class AnthropicLlmProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> EFFORT_MODELS = List.of("claude-sonnet-5-5", "claude-opus-5-5");

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        AnthropicLlmProvider.forgetLearnedNoTemperatureModels();
    }

    /** A local server answering every request from {@code handler}; returns the messages URL. */
    private String serve(java.util.function.Function<String, String[]> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] statusAndBody = handler.apply(request);
            byte[] bytes = statusAndBody[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(statusAndBody[0]), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private static final String TEMPERATURE_400 = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"temperature is deprecated for this model\"}}";

    private static String fixture(String name) throws Exception {
        try (InputStream is = new ClassPathResource("anthropic/" + name).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static LlmResponse parseFixture(String name, String model) throws Exception {
        return AnthropicLlmProvider.parseResponse(MAPPER.readTree(fixture(name)), model);
    }

    private AnthropicLlmProvider providerFor(String model) {
        return new AnthropicLlmProvider("test-key-not-real", model, 10.0, null, EFFORT_MODELS, 32000);
    }

    // ---------------------------------------------------------------- response parsing

    @Test
    void thinkingBlockThenTextBlock_parsesTheTextBlock() throws Exception {
        LlmResponse r = parseFixture("thinking_then_text.json", "claude-sonnet-5-5");

        assertFalse(r.failed());
        JsonNode json = MAPPER.readTree(r.content());
        assertTrue(json.has("notice") && json.has("issues"), "the JSON comes from the text block, not the empty thinking block");
        assertEquals("FAKE-SCN-001", json.path("notice").path("notice_number").asText());
        assertEquals("end_turn", r.stopReason());
        assertEquals(1200, r.inputTokens());
        assertEquals(3400, r.outputTokens());
        assertEquals(9000, r.cacheCreationInputTokens());
        assertEquals(0, r.cacheReadInputTokens());
    }

    @Test
    void multipleTextBlocks_areConcatenated_otherBlocksSkipped() throws Exception {
        LlmResponse r = parseFixture("multi_text.json", "claude-opus-5-5");

        assertFalse(r.failed());
        JsonNode json = MAPPER.readTree(r.content());
        assertEquals("<p>Part one and part two</p>", json.path("section04_html").asText());
        assertFalse(r.content().contains("fixture-redacted"));
        assertEquals(7000, r.cacheReadInputTokens());
    }

    @Test
    void onlyThinkingBlock_isFailure_butUsageIsKept() throws Exception {
        LlmResponse r = parseFixture("thinking_only.json", "claude-sonnet-5-5");

        assertTrue(r.failed());
        assertEquals(LlmResponse.FAILURE_NO_TEXT, r.failureReason());
        assertEquals("", r.content());
        assertEquals(2500, r.outputTokens(), "billed thinking tokens are still reported");
    }

    @Test
    void maxTokensStop_isFailure_evenWithPartialText() throws Exception {
        LlmResponse r = parseFixture("max_tokens.json", "claude-sonnet-5-5");

        assertTrue(r.failed());
        assertTrue(r.truncated());
        assertFalse(r.refused());
        assertEquals("", r.content(), "partial JSON must never be handed to callers");
        assertEquals(8000, r.outputTokens());
    }

    @Test
    void refusalStop_isFailure() throws Exception {
        LlmResponse r = parseFixture("refusal.json", "claude-opus-5-5");

        assertTrue(r.failed());
        assertTrue(r.refused());
        assertFalse(r.truncated());
        assertEquals(40, r.outputTokens());
    }

    // ---------------------------------------------------------------- request body

    @Test
    void requestBody_cachedUserPrefix_isFirstContentBlockWithCacheControl() {
        AnthropicLlmProvider p = providerFor("claude-opus-5-5");
        ObjectNode body = p.buildRequestBody("system", "NOTICE CONTEXT ...", "ISSUE ...", "claude-opus-5-5", 8000, 0.0, "medium", true);

        var content = body.path("messages").get(0).path("content");
        assertTrue(content.isArray());
        assertEquals(2, content.size());
        assertEquals("NOTICE CONTEXT ...", content.get(0).path("text").asText());
        assertEquals("ephemeral", content.get(0).path("cache_control").path("type").asText());
        assertEquals("ISSUE ...", content.get(1).path("text").asText());
        assertFalse(content.get(1).has("cache_control"), "the per-issue block is not cached");
        assertEquals("ephemeral", body.path("system").get(0).path("cache_control").path("type").asText());
        assertEquals("medium", body.path("output_config").path("effort").asText());
        for (String banned : List.of("thinking", "temperature", "top_p", "top_k")) {
            assertFalse(body.has(banned));
        }

        ObjectNode plain = p.buildRequestBody("system", null, "user", "claude-opus-5-5", 8000, 0.0, "medium", true);
        assertEquals("user", plain.path("messages").get(0).path("content").asText(), "no prefix: plain string content as before");
    }

    @Test
    void requestBody_forThinkingModels_hasEffort_andNoSamplingOrThinkingParams() {
        for (String model : EFFORT_MODELS) {
            AnthropicLlmProvider p = providerFor(model);
            // even a caller passing temperature must not leak it to these models
            ObjectNode body = p.buildRequestBody("system", "user", model, 8000, 0.7, "low", true);

            assertEquals("low", body.path("output_config").path("effort").asText(), model);
            assertFalse(body.has("effort"), "effort goes inside output_config, not top level");
            for (String banned : List.of("thinking", "temperature", "top_p", "top_k")) {
                assertFalse(body.has(banned), model + " body must not contain " + banned);
            }
            assertEquals(8000, body.path("max_tokens").asInt());
            assertEquals("ephemeral", body.path("system").get(0).path("cache_control").path("type").asText(),
                    "prompt caching on the system block is unchanged");
        }
    }

    @Test
    void requestBody_legacyCallPathForThinkingModel_dropsTemperature() {
        AnthropicLlmProvider p = providerFor("claude-opus-5-5");
        ObjectNode body = p.buildRequestBody("s", "u", "claude-opus-5-5", 4000, 0.2, null, true);
        assertFalse(body.has("temperature"));
        assertFalse(body.has("output_config"));
    }

    @Test
    void requestBody_otherModels_keepLegacyBehaviour() {
        AnthropicLlmProvider p = providerFor("claude-3-5-haiku-20241022");
        ObjectNode body = p.buildRequestBody("s", "u", "claude-3-5-haiku-20241022", 4000, 0.2, "low", true);
        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9);
        assertFalse(body.has("output_config"), "effort is only sent to configured effort models");
    }

    @Test
    void withTemperature_sendsZeroToSamplingModels_andNothingToThinkingModels() {
        AnthropicLlmProvider p = providerFor("claude-3-5-haiku-20241022");
        AnthropicLlmProvider fixed = (AnthropicLlmProvider) p.withTemperature(0.0);
        assertEquals(0.0, fixed.fixedTemperature());
        assertNull(p.fixedTemperature(), "the original provider is unchanged");
        ObjectNode body = fixed.buildRequestBody("s", "u", "claude-3-5-haiku-20241022", 4000, 0.0, "low", true);
        assertTrue(body.has("temperature"), "temperature 0 is sent explicitly, not left to the API default");
        assertEquals(0.0, body.path("temperature").asDouble(), 1e-9);
        assertFalse(p.buildRequestBody("s", "u", "claude-3-5-haiku-20241022", 4000, 0.0, "low", true).has("temperature"),
                "without withTemperature a 0.0 is not sent (legacy rule)");

        // thinking models reject sampling parameters: the effort setting stays, no temperature
        AnthropicLlmProvider thinking = (AnthropicLlmProvider) providerFor("claude-opus-5-5").withTemperature(0.0);
        ObjectNode effortBody = thinking.buildRequestBody("s", "u", "claude-opus-5-5", 4000, 0.0, "low", true);
        assertFalse(effortBody.has("temperature"));
        assertEquals("low", effortBody.path("output_config").path("effort").asText());
        // models that deprecate temperature never get one either
        assertFalse(((AnthropicLlmProvider) providerFor("claude-sonnet-4-20250514").withTemperature(0.0))
                .buildRequestBody("s", "u", "claude-sonnet-4-20250514", 4000, 0.0, null, true).has("temperature"));
    }

    @Test
    void fiveFiveModels_neverGetTemperature_evenWhenFixed() {
        // sampling path (none of these is in the test's effort list): the known rule must still drop the field
        for (String model : List.of("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5-20261001",
                "claude-future-5-5", "claude-sonnet-4-20250514")) {
            AnthropicLlmProvider p = new AnthropicLlmProvider("k", model, 10.0, null, List.of(), 32000);
            assertTrue(AnthropicLlmProvider.modelDeprecatesTemperature(model), model);
            ObjectNode fixed = ((AnthropicLlmProvider) p.withTemperature(0.0)).buildRequestBody("s", "u", model, 4000, 0.0, "low", true);
            assertFalse(fixed.has("temperature"), "withTemperature must not send temperature to " + model);
            assertFalse(p.buildRequestBody("s", "u", model, 4000, 0.2, null, true).has("temperature"), "legacy path " + model);
        }
        // a model that takes one still gets it (the rule is not "every 5.x model")
        assertFalse(AnthropicLlmProvider.modelDeprecatesTemperature("claude-3-5-haiku-20241022"));
        assertFalse(AnthropicLlmProvider.modelDeprecatesTemperature("claude-test-sampler-1"));
    }

    @Test
    void temperatureRejectedByA400_isRetriedOnceWithoutIt_andTheModelIsRemembered() throws Exception {
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        String ok = fixture("multi_text.json");
        String url = serve(request -> {
            JsonNode sent;
            try {
                sent = MAPPER.readTree(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            requests.add(sent);
            return sent.has("temperature") ? new String[]{"400", TEMPERATURE_400} : new String[]{"200", ok};
        });
        String model = "claude-test-sampler-1";   // not in any known list: the rule has to be learnt from the 400
        assertFalse(AnthropicLlmProvider.modelDeprecatesTemperature(model));
        LlmProvider fixed = new AnthropicLlmProvider("test-key-not-real", model, 10.0, null, EFFORT_MODELS, 32000, url)
                .withTemperature(0.0);

        LlmResponse r = fixed.generateWithEffort("system prompt", "user prompt", model, 4000, "low", true);

        assertFalse(r.failed(), "the retry without temperature is the call's result");
        assertEquals(2, requests.size(), "one 400, one retry");
        assertTrue(requests.get(0).has("temperature"), "the first attempt sent temperature 0");
        assertFalse(requests.get(1).has("temperature"), "the retry is the same request without the field");
        assertEquals(requests.get(0).path("max_tokens"), requests.get(1).path("max_tokens"));
        assertEquals(requests.get(0).path("messages"), requests.get(1).path("messages"));
        assertTrue(AnthropicLlmProvider.modelDeprecatesTemperature(model), "remembered in-process");
        assertTrue(AnthropicLlmProvider.learnedNoTemperatureModels().contains(model));

        // a later call (another instance of the same model) sends no temperature and makes one request
        LlmProvider again = new AnthropicLlmProvider("test-key-not-real", model, 10.0, null, EFFORT_MODELS, 32000, url)
                .withTemperature(0.0);
        assertFalse(again.generateWithEffort("system prompt", "user prompt", model, 4000, "low", true).failed());
        assertEquals(3, requests.size());
        assertFalse(requests.get(2).has("temperature"));
    }

    @Test
    void temperatureRejection_isAlsoReadFromNotSupported_butOther400sAreNotRetried() throws Exception {
        assertTrue(AnthropicLlmProvider.isTemperatureRejection(new LlmException(
                "anthropic error: HTTP 400 - {\"error\":{\"message\":\"`temperature` is not supported for this model\"}}")));
        assertFalse(AnthropicLlmProvider.isTemperatureRejection(new LlmException("anthropic error: HTTP 400 - max_tokens too large")));
        assertFalse(AnthropicLlmProvider.isTemperatureRejection(new LlmTransientException("HTTP 529 temperature deprecated")));
        assertFalse(AnthropicLlmProvider.isTemperatureRejection(new LlmFatalException("HTTP 400 temperature deprecated billing")));

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String url = serve(request -> {
            calls.incrementAndGet();
            return new String[]{"400", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: too large\"}}"};
        });
        LlmProvider p = new AnthropicLlmProvider("test-key-not-real", "claude-test-sampler-2", 10.0, null, EFFORT_MODELS, 32000, url)
                .withTemperature(0.0);
        LlmException e = assertThrows(LlmException.class,
                () -> p.generateWithEffort("system prompt", "user prompt", "claude-test-sampler-2", 4000, "low", true));
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertEquals(1, calls.get(), "an unrelated 400 is not retried");
        assertFalse(AnthropicLlmProvider.modelDeprecatesTemperature("claude-test-sampler-2"));
    }

    @Test
    void maxTokens_isClampedToConfiguredCeiling() {
        AnthropicLlmProvider p = new AnthropicLlmProvider("k", "claude-sonnet-5-5", 10.0, null, EFFORT_MODELS, 12000);
        ObjectNode body = p.buildRequestBody("s", "u", "claude-sonnet-5-5", 16000, 0.0, "low", true);
        assertEquals(12000, body.path("max_tokens").asInt());
    }

    // ---------------------------------------------------------------- full HTTP round trip (mocked server)

    @Test
    void httpRoundTrip_sendsEffortBody_andParsesThinkingResponse() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        String responseJson = fixture("thinking_then_text.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";

        AnthropicLlmProvider p = new AnthropicLlmProvider("test-key-not-real", "claude-sonnet-5-5", 10.0, null,
                EFFORT_MODELS, 32000, url);
        LlmResponse r = p.generateWithEffort("system prompt", "user prompt", "claude-sonnet-5-5", 8000, "low", true);

        assertFalse(r.failed());
        assertTrue(MAPPER.readTree(r.content()).has("issues"));

        JsonNode sent = MAPPER.readTree(captured.get());
        assertEquals("low", sent.path("output_config").path("effort").asText());
        assertEquals(8000, sent.path("max_tokens").asInt());
        for (String banned : List.of("thinking", "temperature", "top_p", "top_k")) {
            assertFalse(sent.has(banned), "sent body must not contain " + banned);
        }
    }
}
