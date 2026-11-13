package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The IndianKanoon search call as sent and as read (fix 3 of the live run of 13-11-2026, where several real cases came
 * back NOT_FOUND with nothing in the log to say what the API had returned): URL form and single URL-encoding, the fields
 * read from {@code docs[]}, and the per-search log line. A local stub server answers; no live call.
 */
class IndianKanoonClientTest {

    private static final ObjectMapper OM = new ObjectMapper();
    /** The shape of a live answer: the fields read plus others the API sends (headline, numcites ...). */
    private static final String ANSWER = """
            {"docs": [
              {"tid": 101, "title": "Union Of India vs Bharti Airtel Ltd. &amp; Ors on 28 October, 2021",
               "docsource": "Supreme Court of India", "publishdate": "2021-10-28", "headline": "...<b>Bharti</b>...", "numcites": 12},
              {"tid": 102, "title": "Bharti Airtel Ltd vs Union Of India on 5 May, 2020", "docsource": "Delhi High Court", "publishdate": "2020-05-05"},
              {"tid": 103, "title": "Third Case vs Fourth Party on 1 January, 2019", "docsource": "Bombay High Court", "publishdate": "2019-01-01"},
              {"tid": 104, "title": "Fifth vs Sixth on 2 February, 2018", "docsource": "Madras High Court", "publishdate": "2018-02-02"},
              {"tid": 105, "title": "Seventh vs Eighth on 3 March, 2017", "docsource": "Kerala High Court", "publishdate": "2017-03-03"},
              {"tid": 106, "title": "Ninth vs Tenth on 4 April, 2016", "docsource": "Gujarat High Court", "publishdate": "2016-04-04"},
              {"title": "No Id vs Nobody", "docsource": "CESTAT"}
            ], "found": "7 of 7", "encoded_json": "x"}
            """;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private record Request(String method, String path, String query, String auth) {}

    /** Serves {@code status} / {@code body} to every request and records them. */
    private String serve(int status, String body, List<Request> requests) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void searchUrlIsTheDocumentedForm_encodedOnce() {
        String url = IndianKanoonClient.searchUrl("Bharti Airtel Ltd v. Union of India & Ors");
        assertEquals("https://api.indiankanoon.org/search/?formInput=Bharti+Airtel+Ltd+v.+Union+of+India+%26+Ors&pagenum=0", url);
        String formInput = url.substring(url.indexOf("formInput=") + 10, url.indexOf("&pagenum"));
        assertEquals("Bharti Airtel Ltd v. Union of India & Ors", URLDecoder.decode(formInput, StandardCharsets.UTF_8),
                "decoding once gives the query back: v. and & survive, nothing is double-encoded");
        assertFalse(url.contains("%25"), "no double encoding");
        assertTrue(IndianKanoonClient.searchUrl("Suncraft Energy vs Assistant Commissioner").contains("formInput=Suncraft+Energy+vs+Assistant+Commissioner&pagenum=0"));
    }

    @Test
    void searchPostsTheQueryWithTheTokenHeader_readsTheDocFields_andLogsTheTopFive() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        List<String> logged = new CopyOnWriteArrayList<>();
        IndianKanoonClient client = new IndianKanoonClient(OM, serve(200, ANSWER, requests));
        client.setSearchLogSink(logged::add);

        List<IkDoc> docs = client.search("tok-not-real", "Bharti Airtel Ltd v. Union of India");

        Request req = requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("/search/", req.path());
        assertEquals("formInput=Bharti+Airtel+Ltd+v.+Union+of+India&pagenum=0", req.query());
        assertEquals("Token tok-not-real", req.auth());

        assertEquals(7, docs.size());
        assertEquals(new IkDoc("101", "Union Of India vs Bharti Airtel Ltd. &amp; Ors on 28 October, 2021", "Supreme Court of India", "2021-10-28"), docs.get(0));
        assertEquals("102", docs.get(1).docId());
        assertEquals(new IkDoc(null, "No Id vs Nobody", "CESTAT", ""), docs.get(6), "missing fields: tid null, publishdate empty");

        assertEquals(1, logged.size());
        String line = logged.get(0);
        assertTrue(line.startsWith("ik_search formInput=\"Bharti Airtel Ltd v. Union of India\" status=200 docs=7 top5=[1. Union Of India vs Bharti Airtel Ltd. &amp; Ors on 28 October, 2021 | Supreme Court of India | 2021-10-28; 2. Bharti Airtel Ltd vs Union Of India on 5 May, 2020 | Delhi High Court | 2020-05-05; 3. "), line);
        assertTrue(line.contains("5. Seventh vs Eighth on 3 March, 2017 | Kerala High Court | 2017-03-03]"), line);
        assertFalse(line.contains("Ninth"), "only the first five hits are listed: " + line);

        // the comparison helper over the same answer: the matching title is the first hit here, the second elsewhere
        CitationVerificationAgent.Hit hit = CitationVerificationAgent.firstMatchingHit("Bharti Airtel Ltd v. Union of India", docs);
        assertEquals(1, hit.position());
        assertEquals("101", hit.doc().docId());
        CitationVerificationAgent.Hit second = CitationVerificationAgent.firstMatchingHit("Bharti Airtel Ltd v. Union of India", docs.subList(1, 7));
        assertEquals("102", second.doc().docId(), "the Delhi case of 2020 also names both parties (court / year are checked after)");
        assertNull(CitationVerificationAgent.firstMatchingHit("Bharti Airtel Ltd v. Union of India", docs.subList(2, 7)));
        assertNull(CitationVerificationAgent.firstMatchingHit("Nobody Here v. Nothing", docs));
    }

    @Test
    void nonOkStatusIsLoggedThenThrown_fatalForAuthAndRateLimit() throws Exception {
        List<String> logged = new CopyOnWriteArrayList<>();
        IndianKanoonClient server500 = new IndianKanoonClient(OM, serve(500, "{\"error\":\"x\"}", new CopyOnWriteArrayList<>()));
        server500.setSearchLogSink(logged::add);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> server500.search("tok", "A v. B"));
        assertEquals("IndianKanoon HTTP 500", e.getMessage());
        assertEquals("ik_search formInput=\"A v. B\" status=500 docs=0", logged.get(0));
        server.stop(0);

        IndianKanoonClient server401 = new IndianKanoonClient(OM, serve(401, "{}", new CopyOnWriteArrayList<>()));
        IndianKanoonFatalException fatal = assertThrows(IndianKanoonFatalException.class, () -> server401.search("tok", "A v. B"));
        assertEquals(401, fatal.status());
    }

    @Test
    void parseDocsAndDescribeTop() throws Exception {
        List<IkDoc> docs = IndianKanoonClient.parseDocs(OM.readValue(ANSWER, Map.class));
        assertEquals(7, docs.size());
        assertEquals("[]", IndianKanoonClient.describeTop(List.of(), 5));
        assertEquals("[1. Fifth vs Sixth on 2 February, 2018 | Madras High Court | 2018-02-02]", IndianKanoonClient.describeTop(docs.subList(3, 4), 5));
        assertTrue(IndianKanoonClient.parseDocs(Map.of("found", "0 of 0")).isEmpty(), "no docs key");
        assertTrue(IndianKanoonClient.parseDocs(null).isEmpty());

        // note() with no sink only logs
        AtomicReference<String> seen = new AtomicReference<>();
        IndianKanoonClient c = new IndianKanoonClient(OM);
        c.note("ik_compare case=\"x\"");
        c.setSearchLogSink(seen::set);
        c.note("second");
        assertEquals("second", seen.get());
    }
}
