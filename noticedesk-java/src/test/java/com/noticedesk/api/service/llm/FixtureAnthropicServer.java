package com.noticedesk.api.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * Local stand-in for the Messages API: answers each POST with the next queued fixture from
 * {@code src/test/resources/llm-fixtures/} (status + body) and records the request bodies.
 * Agents run end-to-end through a real {@link AnthropicLlmProvider}, so response parsing is tested
 * offline at zero cost. When the queue is empty it answers HTTP 500 (a test asking for more calls fails).
 */
public final class FixtureAnthropicServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Reply(int status, String body) {}

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();

    public FixtureAnthropicServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Reply r;
            synchronized (this) {
                requests.add(MAPPER.readTree(in));
                r = replies.isEmpty() ? new Reply(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no fixture queued\"}}")
                        : replies.poll();
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    /** Queues a 200 response with the body of {@code llm-fixtures/<name>}. */
    public FixtureAnthropicServer reply(String fixture) throws Exception {
        return reply(200, fixture);
    }

    public synchronized FixtureAnthropicServer reply(int status, String fixture) throws Exception {
        replies.add(new Reply(status, fixture(fixture)));
        return this;
    }

    public synchronized List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    public synchronized int callCount() {
        return requests.size();
    }

    /** A provider whose calls go to this server; {@code effortModels} get output_config.effort. */
    public AnthropicLlmProvider provider(String model, Collection<String> effortModels) {
        return new AnthropicLlmProvider("test-key-not-real", model, 10.0, null, effortModels, 32000,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
    }

    public static String fixture(String name) throws Exception {
        try (InputStream is = new ClassPathResource("llm-fixtures/" + name).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
