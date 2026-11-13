package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.function.Consumer;

/**
 * Thin HTTP client for the IndianKanoon API (search + document fragment). Kept separate so tests
 * can mock it; no test makes a live call. HTTP 401 / 403 / 429 throw {@link IndianKanoonFatalException}.
 *
 * <p>Search is the documented form {@code POST https://api.indiankanoon.org/search/?formInput=<query>&pagenum=0}
 * with the {@code Authorization: Token <token>} header and no body; the query is URL-encoded exactly once
 * ({@link #searchUrl}: "v." and "vs" pass unchanged, "&" becomes "%26", a space "+"). The answer's
 * {@code docs[].tid / title / docsource / publishdate} are read into {@link IkDoc} ({@link #parseDocs}).
 *
 * <p>Every search is logged at INFO (and sent to the optional {@link #setSearchLogSink sink}, which the live runners
 * point at the console) as one line: the exact {@code formInput} sent, the HTTP status, the number of docs returned and
 * the first {@value #LOGGED_TOP_HITS} titles with their docsource and publishdate. Judgment titles are public; notice
 * text never passes through this client.
 */
@Component
@Slf4j
public class IndianKanoonClient {

    private static final String BASE_URL = "https://api.indiankanoon.org";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Hits named in the per-search log line. */
    public static final int LOGGED_TOP_HITS = 5;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    /** Optional extra receiver of the per-search log lines (the live runners print them); null = log only. */
    private volatile Consumer<String> searchLogSink;

    @org.springframework.beans.factory.annotation.Autowired
    public IndianKanoonClient(ObjectMapper objectMapper) {
        this(objectMapper, BASE_URL);
    }

    /** {@code baseUrl} is overridable for tests against a local stub server. */
    IndianKanoonClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /** One search hit: document id, title, court/source and publish date (yyyy-MM-dd or similar). */
    public record IkDoc(String docId, String title, String docSource, String publishDate) {}

    /** Also sends every per-search log line (and the agent's comparison lines) to {@code sink}; null stops that. */
    public void setSearchLogSink(Consumer<String> sink) {
        this.searchLogSink = sink;
    }

    /** Logs one line at INFO and to the sink. Used for the search lines here and the comparison lines of the agent. */
    public void note(String line) {
        log.info(line);
        Consumer<String> sink = searchLogSink;
        if (sink != null) sink.accept(line);
    }

    /** The search URL for {@code query}: the documented form, the query URL-encoded once. */
    static String searchUrl(String baseUrl, String query) {
        return baseUrl + "/search/?formInput=" + enc(query) + "&pagenum=0";
    }

    /** {@link #searchUrl(String, String)} against the live base URL. */
    public static String searchUrl(String query) {
        return searchUrl(BASE_URL, query);
    }

    /** Search judgments by free text. Throws on transport / HTTP errors. */
    public List<IkDoc> search(String token, String query) throws Exception {
        String url = searchUrl(baseUrl, query);
        HttpResponse<String> response = send(token, url);
        if (response.statusCode() != 200) {
            note("ik_search formInput=\"" + query + "\" status=" + response.statusCode() + " docs=0");
            throw httpError(response.statusCode());
        }
        List<IkDoc> out = parseDocs(objectMapper.readValue(response.body(), new TypeReference<>() {}));
        note("ik_search formInput=\"" + query + "\" status=200 docs=" + out.size() + " top" + LOGGED_TOP_HITS + "="
                + describeTop(out, LOGGED_TOP_HITS));
        return out;
    }

    /** The {@code docs[]} of a search answer as {@link IkDoc}s (missing fields read as empty, {@code tid} as null). */
    static List<IkDoc> parseDocs(Map<String, Object> body) {
        List<IkDoc> out = new ArrayList<>();
        if (body != null && body.get("docs") instanceof List<?> docs) {
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

    /** "[1. <title> | <docsource> | <publishdate>; 2. ...]" for the first {@code n} hits ("[]" for none). */
    static String describeTop(List<IkDoc> docs, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docs.size() && i < n; i++) {
            IkDoc d = docs.get(i);
            if (i > 0) sb.append("; ");
            sb.append(i + 1).append(". ").append(d.title()).append(" | ").append(d.docSource()).append(" | ").append(d.publishDate());
        }
        return sb.append(']').toString();
    }

    /** Returns the text of the fragments of {@code docId} that match {@code query} (HTML stripped). */
    public String fragment(String token, String docId, String query) throws Exception {
        String url = baseUrl + "/docfragment/" + enc(docId) + "/?formInput=" + enc(query);
        HttpResponse<String> response = send(token, url);
        if (response.statusCode() != 200) throw httpError(response.statusCode());
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        StringBuilder sb = new StringBuilder();
        Object headline = body.get("headline");
        if (headline instanceof List<?> list) {
            for (Object o : list) sb.append(o).append(' ');
        } else if (headline != null) {
            sb.append(headline);
        }
        return sb.toString().replaceAll("<[^>]+>", "");
    }

    private HttpResponse<String> send(String token, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Token " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 401 / 403 / 429 stop the run (no retry, no NOT_CHECKED fallback); other errors are per-citation. */
    private static RuntimeException httpError(int status) {
        IndianKanoonFatalException fatal = IndianKanoonFatalException.forHttpStatus(status);
        if (fatal != null) return fatal;
        return new IllegalStateException("IndianKanoon HTTP " + status);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
