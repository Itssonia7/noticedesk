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
    }

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
