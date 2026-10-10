package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the IndianKanoon API (search + document fragment). Kept separate so tests
 * can mock it; no test makes a live call.
 */
@Component
@RequiredArgsConstructor
public class IndianKanoonClient {

    private static final String BASE_URL = "https://api.indiankanoon.org";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** One search hit: document id, title, court/source and publish date (yyyy-MM-dd or similar). */
    public record IkDoc(String docId, String title, String docSource, String publishDate) {}

    /** Search judgments by free text. Throws on transport / HTTP errors. */
    public List<IkDoc> search(String token, String query) throws Exception {
        String url = BASE_URL + "/search/?formInput=" + enc(query) + "&pagenum=0";
        Map<String, Object> body = post(token, url);
        List<IkDoc> out = new ArrayList<>();
        if (body.get("docs") instanceof List<?> docs) {
            for (Object o : docs) {
                if (o instanceof Map<?, ?> d) {
                    out.add(new IkDoc(
                            d.get("tid") != null ? d.get("tid").toString() : null,
                            d.get("title") != null ? d.get("title").toString() : "",
                            d.get("docsource") != null ? d.get("docsource").toString() : "",
                            d.get("publishdate") != null ? d.get("publishdate").toString() : ""));
                }
            }
        }
        return out;
    }

    /** Returns the text of the fragments of {@code docId} that match {@code query} (HTML stripped). */
    public String fragment(String token, String docId, String query) throws Exception {
        String url = BASE_URL + "/docfragment/" + enc(docId) + "/?formInput=" + enc(query);
        Map<String, Object> body = post(token, url);
        StringBuilder sb = new StringBuilder();
        Object headline = body.get("headline");
        if (headline instanceof List<?> list) {
            for (Object o : list) sb.append(o).append(' ');
        } else if (headline != null) {
            sb.append(headline);
        }
        return sb.toString().replaceAll("<[^>]+>", "");
    }

    private Map<String, Object> post(String token, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Token " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("IndianKanoon HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
