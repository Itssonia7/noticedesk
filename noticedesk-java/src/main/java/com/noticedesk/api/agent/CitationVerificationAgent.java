package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CitationVerificationAgent {

    private static final Pattern CITATION_RE = Pattern.compile(
            "([A-Za-z][^\\n.]{2,60})\\s+v\\.\\s+([A-Za-z][^\\n,.(]{2,60})",
            Pattern.CASE_INSENSITIVE);

    private static final String IK_SEARCH_URL = "https://api.indiankanoon.org/search/";
    private static final String IK_FRAGMENT_URL = "https://api.indiankanoon.org/docfragment/";
    private static final int HTTP_TIMEOUT_SECS = 10;

    private static final Set<String> WHITELIST = Set.of(
            "section 16", "section 50", "section 73", "section 74", "section 75",
            "section 16(2)(aa)", "proviso to section 50(1)", "bharti airtel", "vkc footsteps"
    );

    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final ApiUsageLogService apiUsageLogService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final IndianKanoonClient ikClient;

    @Autowired
    public CitationVerificationAgent(ObjectMapper objectMapper,
                                     AppProperties properties,
                                     @Autowired(required = false) ApiUsageLogService apiUsageLogService,
                                     @Autowired(required = false) NamedParameterJdbcTemplate jdbcTemplate,
                                     IndianKanoonClient ikClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.apiUsageLogService = apiUsageLogService;
        this.jdbcTemplate = jdbcTemplate;
        this.ikClient = ikClient;
    }

    public CitationVerificationAgent(ObjectMapper objectMapper, AppProperties properties,
                                     ApiUsageLogService apiUsageLogService, NamedParameterJdbcTemplate jdbcTemplate) {
        this(objectMapper, properties, apiUsageLogService, jdbcTemplate, new IndianKanoonClient(objectMapper));
    }

    public CitationVerificationAgent(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null);
    }

    public record VerifiedCitation(
            String caseName,
            String citationString,
            String rawText,
            String status,                       // VERIFIED | VERIFIED_PARTIAL | NOT_FOUND | NOT_CHECKED
            String sourceUrl,
            String verifiedParagraphText,
            Double propositionMatchConfidence,
            String actionTaken,                  // passed | flagged | stripped
            String details,
            String court,
            Integer year,
            String quotedText
    ) {}

    public record CitationVerificationResult(
            Map<String, Object> summary,
            List<VerifiedCitation> citations,
            String cleanedHtml,
            List<String> flags
    ) {}

    public CitationVerificationResult verify(String draftText, String citationProvider) {
        List<RawAiCitation> extracted = extractCitationsFromText(draftText);
        return verifyStructuredCitations(extracted, draftText, citationProvider, null);
    }

    private List<RawAiCitation> extractCitationsFromText(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<RawAiCitation> list = new ArrayList<>();
        Matcher m = CITATION_RE.matcher(text);
        while (m.find()) {
            String cName = (m.group(1).strip() + " v. " + m.group(2).strip()).trim();
            list.add(new RawAiCitation(cName, null, null, null, null));
        }
        return list;
    }

    public CitationVerificationResult verifyStructuredCitations(
            List<RawAiCitation> rawCitations,
            String fullHtml,
            String provider,
            UUID noticeId
    ) {
        if (rawCitations == null || rawCitations.isEmpty()) {
            return new CitationVerificationResult(
                    Map.of("total", 0, "verified", 0, "partial", 0, "unverified", 0, "removed", 0),
                    List.of(), fullHtml, List.of());
        }

        String effectiveProvider = provider;
        if (effectiveProvider == null || effectiveProvider.isBlank()) {
            effectiveProvider = (properties != null && properties.getCitation() != null) ?
                    properties.getCitation().getProvider() : "stub";
        }

        if ("stub".equalsIgnoreCase(effectiveProvider)) {
            List<VerifiedCitation> stubList = new ArrayList<>();
            for (RawAiCitation r : rawCitations) {
                stubList.add(new VerifiedCitation(
                        r.caseName(), r.caseName(), r.caseName(), "VERIFIED",
                        "https://indiankanoon.org/doc/stub/", null, 0.90, "passed",
                        "Dev stub auto-passed", r.court(), r.year(), r.quotedText()));
            }
            Map<String, Object> summary = Map.of("total", stubList.size(), "verified", stubList.size(), "partial", 0, "unverified", 0, "removed", 0);
            return new CitationVerificationResult(summary, stubList, fullHtml, List.of());
        }

        // Check token for IndianKanoon
        String token = (properties != null && properties.getCitation() != null) ?
                properties.getCitation().getIndiankanoonApiToken() : null;
        if (token == null || token.isBlank()) {
            token = System.getenv("INDIANKANOON_API_TOKEN");
        }

        if (token == null || token.isBlank()) {
            log.warn("INDIANKANOON_API_TOKEN is missing. Marking citations NOT_CHECKED and setting block flag.");
            List<VerifiedCitation> notChecked = new ArrayList<>();
            for (RawAiCitation r : rawCitations) {
                notChecked.add(new VerifiedCitation(
                        r.caseName(), r.caseName(), r.caseName(), "NOT_CHECKED",
                        null, null, 0.0, "flagged",
                        "Missing IndianKanoon API Token", r.court(), r.year(), r.quotedText()));
            }
            Map<String, Object> summary = Map.of("total", notChecked.size(), "verified", 0, "partial", 0, "unverified", notChecked.size(), "removed", 0);
            return new CitationVerificationResult(summary, notChecked, fullHtml, List.of("MISSING_INDIANKANOON_API_TOKEN"));
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECS))
                .build();

        int maxCalls = (properties != null && properties.getCitation() != null) ?
                properties.getCitation().getMaxCallsPerDraft() : 15;

        List<VerifiedCitation> verifiedList = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        String currentHtml = fullHtml;
        int callsMade = 0;
        int countVerified = 0, countPartial = 0, countUnverified = 0, countRemoved = 0;

        for (RawAiCitation r : rawCitations) {
            String cName = r.caseName() != null ? r.caseName().toLowerCase() : "";

            // 1. Whitelist check
            if (isWhitelisted(cName)) {
                verifiedList.add(new VerifiedCitation(
                        r.caseName(), r.caseName(), r.caseName(), "VERIFIED",
                        null, null, 1.0, "passed",
                        "Skipped API check: Whitelisted statutory term or leading authority", r.court(), r.year(), r.quotedText()));
                countVerified++;
                continue;
            }

            // 2. Cap check
            if (callsMade >= maxCalls) {
                flags.add("IK_MAX_CALLS_EXCEEDED");
                verifiedList.add(new VerifiedCitation(
                        r.caseName(), r.caseName(), r.caseName(), "NOT_CHECKED",
                        null, null, 0.0, "flagged",
                        "Exceeded max IK API calls limit (" + maxCalls + ")", r.court(), r.year(), r.quotedText()));
                countUnverified++;
                continue;
            }

            // 3. Cache check
            String cacheKey = r.caseName() + "|" + r.court() + "|" + r.year();
            VerifiedCitation cached = getFromCache(cacheKey);
            if (cached != null) {
                verifiedList.add(cached);
                if ("VERIFIED".equals(cached.status())) countVerified++;
                else if ("VERIFIED_PARTIAL".equals(cached.status())) countPartial++;
                else countUnverified++;
                continue;
            }

            // 4. IndianKanoon API Call using HTTP POST method
            callsMade++;
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("indiankanoon", "ik_search", 1, 0, "ik_search", null, noticeId);
            }

            VerifiedCitation result = searchIndianKanoon(httpClient, token, r, noticeId);
            saveToCache(cacheKey, result);
            verifiedList.add(result);

            if ("VERIFIED".equals(result.status())) {
                countVerified++;
            } else if ("NOT_FOUND".equals(result.status())) {
                countRemoved++;
                flags.add("citation removed");
                if (currentHtml != null && r.caseName() != null) {
                    currentHtml = currentHtml.replace(r.caseName(), "");
                }
            } else if ("VERIFIED_PARTIAL".equals(result.status())) {
                countPartial++;
                flags.add("UNVERIFIED_QUOTE");
            } else {
                countUnverified++;
            }
        }

        Map<String, Object> summary = Map.of(
                "total", verifiedList.size(),
                "verified", countVerified,
                "partial", countPartial,
                "unverified", countUnverified,
                "removed", countRemoved
        );

        return new CitationVerificationResult(summary, verifiedList, currentHtml, flags);
    }

    private boolean isWhitelisted(String caseName) {
        if (caseName == null) return false;
        for (String w : WHITELIST) {
            if (caseName.contains(w)) return true;
        }
        return false;
    }

    private VerifiedCitation searchIndianKanoon(HttpClient client, String token, RawAiCitation raw, UUID noticeId) {
        try {
            String formBody = "formInput=" + URLEncoder.encode(raw.caseName(), StandardCharsets.UTF_8) + "&pagenum=0";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(IK_SEARCH_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Token " + token)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("IndianKanoon search returned HTTP {} for '{}'", response.statusCode(), raw.caseName());
                return new VerifiedCitation(raw.caseName(), raw.caseName(), raw.caseName(), "NOT_FOUND", null, null, 0.0, "stripped", "HTTP " + response.statusCode(), raw.court(), raw.year(), raw.quotedText());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) body.getOrDefault("docs", List.of());

            if (docs.isEmpty()) {
                return new VerifiedCitation(raw.caseName(), raw.caseName(), raw.caseName(), "NOT_FOUND", null, null, 0.0, "stripped", "No matching cases found on IndianKanoon", raw.court(), raw.year(), raw.quotedText());
            }

            Map<String, Object> doc = docs.get(0);
            String docId = doc.get("tid") != null ? doc.get("tid").toString() : null;
            String title = doc.get("title") != null ? doc.get("title").toString() : "";
            String docUrl = docId != null ? "https://indiankanoon.org/doc/" + docId + "/" : null;

            // Quote verification if quoted_text is present
            if (raw.quotedText() != null && !raw.quotedText().isBlank() && docId != null) {
                if (apiUsageLogService != null) {
                    apiUsageLogService.logUsage("indiankanoon", "ik_fragment", 1, 0, "ik_fragment", null, noticeId);
                }
                boolean quoteFound = verifyFragmentQuote(client, token, docId, raw.quotedText());
                if (!quoteFound) {
                    return new VerifiedCitation(raw.caseName(), raw.caseName(), raw.caseName(), "VERIFIED_PARTIAL", docUrl, null, 0.70, "flagged", "Case title matched, but quote text not found in document fragment", raw.court(), raw.year(), raw.quotedText());
                }
            }

            return new VerifiedCitation(raw.caseName(), raw.caseName(), raw.caseName(), "VERIFIED", docUrl, null, 0.95, "passed", "Verified against " + title, raw.court(), raw.year(), raw.quotedText());

        } catch (Exception e) {
            log.error("IndianKanoon search failed for '{}': {}", raw.caseName(), e.getMessage());
            return new VerifiedCitation(raw.caseName(), raw.caseName(), raw.caseName(), "NOT_FOUND", null, null, 0.0, "stripped", "API error: " + e.getMessage(), raw.court(), raw.year(), raw.quotedText());
        }
    }

    private boolean verifyFragmentQuote(HttpClient client, String token, String docId, String quote) {
        try {
            String formBody = "formInput=" + URLEncoder.encode(quote, StandardCharsets.UTF_8) + "&path=" + docId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(IK_FRAGMENT_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Token " + token)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(quote);
        } catch (Exception e) {
            log.warn("Fragment quote verification failed: {}", e.getMessage());
            return false;
        }
    }

    private VerifiedCitation getFromCache(String cacheKey) {
        if (jdbcTemplate == null) return null;
        try {
            String sql = "SELECT citation_json FROM citation_cache WHERE cache_key = :key AND fetched_at > NOW() - INTERVAL '30 days'";
            List<String> rows = jdbcTemplate.queryForList(sql, new MapSqlParameterSource("key", cacheKey), String.class);
            if (!rows.isEmpty()) {
                return objectMapper.readValue(rows.get(0), VerifiedCitation.class);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void saveToCache(String cacheKey, VerifiedCitation citation) {
        if (jdbcTemplate == null) return;
        try {
            String sql = """
                    INSERT INTO citation_cache (cache_key, citation_json, fetched_at)
                    VALUES (:key, :json::jsonb, NOW())
                    ON CONFLICT (cache_key) DO UPDATE SET citation_json = EXCLUDED.citation_json, fetched_at = NOW()
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("key", cacheKey)
                    .addValue("json", objectMapper.writeValueAsString(citation));
            jdbcTemplate.update(sql, params);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // v7 Stage 3: verification of structured AI citations via IndianKanoon
    // ---------------------------------------------------------------------

    public static final String VERIFIED = "VERIFIED";
    public static final String VERIFIED_PARTIAL = "VERIFIED_PARTIAL";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_CHECKED = "NOT_CHECKED";

    private static final Set<String> NAME_STOPWORDS = Set.of(
            "ltd", "limited", "pvt", "private", "co", "company", "the", "m", "s", "ms", "and", "inc",
            "corp", "corporation", "llp", "of", "others", "ors", "anr", "another");
    private static final Set<String> COURT_STOPWORDS = Set.of(
            "court", "of", "the", "hon", "ble", "honble", "honourable", "india", "high", "supreme",
            "judicature", "at", "bench");

    /**
     * Verifies every AI citation against IndianKanoon and never throws: a missing token, the per-draft
     * call cap ({@code IK_MAX_CALLS_PER_DRAFT}) or an API error yields NOT_CHECKED.
     * <ul>
     *   <li>VERIFIED — case name, court and year all match a judgment (and the quoted text, if any, is found in it)</li>
     *   <li>VERIFIED_PARTIAL — name matched but court/year differ or the quote was not found (block flag)</li>
     *   <li>NOT_FOUND — no judgment with that name (citation text is removed by the caller; warn flag)</li>
     *   <li>NOT_CHECKED — token missing, cap reached or API error (block flag)</li>
     * </ul>
     * The per-citation status is the only output; the caller edits the HTML.
     */
    public CitationVerificationResult verifyAiCitations(List<RawAiCitation> rawCitations, UUID noticeId) {
        List<RawAiCitation> raws = rawCitations != null ? rawCitations : List.of();
        List<VerifiedCitation> out = new ArrayList<>();
        List<String> flags = new ArrayList<>();

        String token = (properties != null && properties.getCitation() != null)
                ? properties.getCitation().getIndiankanoonApiToken() : null;
        if (token == null || token.isBlank()) {
            token = System.getenv("INDIANKANOON_API_TOKEN");
        }
        boolean tokenMissing = token == null || token.isBlank();
        if (tokenMissing && !raws.isEmpty()) {
            log.warn("INDIANKANOON_API_TOKEN is not set; {} AI citation(s) marked NOT_CHECKED", raws.size());
            flags.add("MISSING_INDIANKANOON_API_TOKEN");
        }

        int maxCalls = (properties != null && properties.getCitation() != null)
                ? properties.getCitation().getMaxCallsPerDraft() : 15;
        int[] callsMade = {0};
        Map<String, VerifiedCitation> seen = new HashMap<>();

        for (RawAiCitation r : raws) {
            String key = citationKey(r);
            VerifiedCitation result = seen.get(key);
            if (result == null) {
                if (tokenMissing) {
                    result = v7Result(r, NOT_CHECKED, null, "flagged", "INDIANKANOON_API_TOKEN not set");
                } else {
                    result = getFromIkCache(key);
                    if (result == null) {
                        result = checkAgainstIndianKanoon(r, token, maxCalls, callsMade, noticeId);
                        if (!NOT_CHECKED.equals(result.status())) {
                            saveToIkCache(key, result);
                        }
                    }
                }
                seen.put(key, result);
            }
            if (NOT_CHECKED.equals(result.status()) && result.details() != null
                    && result.details().startsWith("IK call cap") && !flags.contains("IK_MAX_CALLS_EXCEEDED")) {
                flags.add("IK_MAX_CALLS_EXCEEDED");
            }
            out.add(result);
        }

        long verified = out.stream().filter(c -> VERIFIED.equals(c.status())).count();
        long partial = out.stream().filter(c -> VERIFIED_PARTIAL.equals(c.status())).count();
        long notFound = out.stream().filter(c -> NOT_FOUND.equals(c.status())).count();
        long notChecked = out.stream().filter(c -> NOT_CHECKED.equals(c.status())).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", out.size());
        summary.put("verified", verified);
        summary.put("partial", partial);
        summary.put("removed", notFound);
        summary.put("not_checked", notChecked);
        summary.put("ik_calls", callsMade[0]);
        return new CitationVerificationResult(summary, out, null, flags);
    }

    private VerifiedCitation checkAgainstIndianKanoon(RawAiCitation r, String token, int maxCalls,
                                                      int[] callsMade, UUID noticeId) {
        if (callsMade[0] >= maxCalls) {
            return v7Result(r, NOT_CHECKED, null, "flagged", "IK call cap reached (" + maxCalls + ")");
        }
        try {
            callsMade[0]++;
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("indiankanoon", "ik_search", 1, 0, "ik_search", null, noticeId);
            }
            List<IndianKanoonClient.IkDoc> docs = ikClient.search(token, r.caseName());
            IndianKanoonClient.IkDoc match = null;
            for (IndianKanoonClient.IkDoc d : docs) {
                if (namesMatch(r.caseName(), d.title())) {
                    match = d;
                    break;
                }
            }
            if (match == null) {
                return v7Result(r, NOT_FOUND, null, "stripped", "No judgment with this case name found on IndianKanoon");
            }
            String url = match.docId() != null ? "https://indiankanoon.org/doc/" + match.docId() + "/" : null;

            List<String> mismatches = new ArrayList<>();
            if (!courtMatches(r.court(), match.docSource())) {
                mismatches.add("court (cited: " + r.court() + ", IndianKanoon: " + match.docSource() + ")");
            }
            Integer ikYear = extractYear(match.publishDate(), match.title());
            if (r.year() == null || !r.year().equals(ikYear)) {
                mismatches.add("year (cited: " + r.year() + ", IndianKanoon: " + ikYear + ")");
            }
            if (!mismatches.isEmpty()) {
                return v7Result(r, VERIFIED_PARTIAL, url, "flagged", "Name matched; mismatch in " + String.join(", ", mismatches));
            }

            if (r.quotedText() != null && !r.quotedText().isBlank() && match.docId() != null) {
                if (callsMade[0] >= maxCalls) {
                    return v7Result(r, VERIFIED_PARTIAL, url, "flagged", "Name/court/year matched; quote not checked (IK call cap reached)");
                }
                callsMade[0]++;
                if (apiUsageLogService != null) {
                    apiUsageLogService.logUsage("indiankanoon", "ik_fragment", 1, 0, "ik_fragment", null, noticeId);
                }
                String fragment = ikClient.fragment(token, match.docId(), r.quotedText());
                if (!normaliseText(fragment).contains(normaliseText(r.quotedText()))) {
                    return v7Result(r, VERIFIED_PARTIAL, url, "flagged", "Name/court/year matched; quoted text not found in judgment");
                }
            }
            return v7Result(r, VERIFIED, url, "passed", "Matched IndianKanoon judgment: " + match.title());
        } catch (Exception e) {
            log.warn("IndianKanoon check failed for one citation: {}", e.getClass().getSimpleName());
            return v7Result(r, NOT_CHECKED, null, "flagged", "IndianKanoon API error");
        }
    }

    private VerifiedCitation getFromIkCache(String cacheKey) {
        if (jdbcTemplate == null) return null;
        try {
            int ttlDays = (properties != null && properties.getCitation() != null) ? properties.getCitation().getCacheTtlDays() : 30;
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT citation_json::text FROM ik_citation_cache WHERE cache_key = :key AND fetched_at > NOW() - make_interval(days => :ttl)",
                    new MapSqlParameterSource("key", cacheKey).addValue("ttl", ttlDays), String.class);
            if (!rows.isEmpty()) {
                return objectMapper.readValue(rows.get(0), VerifiedCitation.class);
            }
        } catch (Exception e) {
            log.debug("ik_citation_cache read failed: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    private void saveToIkCache(String cacheKey, VerifiedCitation citation) {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO ik_citation_cache (cache_key, citation_json, fetched_at)
                    VALUES (:key, CAST(:json AS JSONB), NOW())
                    ON CONFLICT (cache_key) DO UPDATE SET citation_json = EXCLUDED.citation_json, fetched_at = NOW()
                    """, new MapSqlParameterSource().addValue("key", cacheKey).addValue("json", objectMapper.writeValueAsString(citation)));
        } catch (Exception e) {
            log.debug("ik_citation_cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    private static VerifiedCitation v7Result(RawAiCitation r, String status, String url, String action, String details) {
        return new VerifiedCitation(r.caseName(), r.caseName(), r.citedFor(), status, url, null,
                VERIFIED.equals(status) ? 1.0 : 0.0, action, details, r.court(), r.year(), r.quotedText());
    }

    private static String citationKey(RawAiCitation r) {
        String quote = r.quotedText() != null ? Integer.toHexString(normaliseText(r.quotedText()).hashCode()) : "-";
        return "v7|" + String.join(" ", partyTokens(r.caseName())) + "|" + normaliseText(Objects.toString(r.court(), ""))
                + "|" + r.year() + "|" + quote;
    }

    static boolean namesMatch(String citedName, String ikTitle) {
        if (citedName == null || ikTitle == null) return false;
        String[] sides = normaliseText(citedName).replace(" versus ", " v ").replace(" vs ", " v ").split(" v ");
        Set<String> titleTokens = new HashSet<>(Arrays.asList(normaliseText(ikTitle).split(" ")));
        int sidesChecked = 0;
        for (String side : sides) {
            List<String> tokens = significant(side, NAME_STOPWORDS);
            if (tokens.isEmpty()) continue;
            sidesChecked++;
            long hit = tokens.stream().filter(titleTokens::contains).count();
            if (hit * 5 < tokens.size() * 4) {  // need >= 80% of each party's words
                return false;
            }
        }
        return sidesChecked > 0;
    }

    static boolean courtMatches(String citedCourt, String ikSource) {
        if (citedCourt == null || citedCourt.isBlank() || ikSource == null || ikSource.isBlank()) return false;
        String cited = normaliseText(citedCourt);
        String source = normaliseText(ikSource);
        if (cited.contains("supreme") != source.contains("supreme")) return false;
        if (cited.contains("high court") != source.contains("high court")) return false;
        Set<String> sourceTokens = new HashSet<>(Arrays.asList(source.split(" ")));
        return sourceTokens.containsAll(significant(cited, COURT_STOPWORDS));
    }

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");

    /** Year of the judgment: from the publish date if present, else the last year in the title ("... on 12 May, 2016"). */
    private static Integer extractYear(String publishDate, String title) {
        if (publishDate != null) {
            Matcher m = YEAR.matcher(publishDate);
            if (m.find()) return Integer.parseInt(m.group());
        }
        Integer last = null;
        if (title != null) {
            Matcher m = YEAR.matcher(title);
            while (m.find()) last = Integer.parseInt(m.group());
        }
        return last;
    }

    private static List<String> partyTokens(String name) {
        return significant(normaliseText(Objects.toString(name, "")).replace(" vs ", " v "), NAME_STOPWORDS);
    }

    private static List<String> significant(String text, Set<String> stop) {
        List<String> out = new ArrayList<>();
        for (String t : text.trim().split(" ")) {
            if (!t.isBlank() && !stop.contains(t)) out.add(t);
        }
        return out;
    }

    static String normaliseText(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace("&amp;", " and ")
                .replaceAll("\\bv(?:s)?\\.", " v ")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Removes the citation of {@code caseName} (and a reporter reference / year immediately after it,
     * e.g. "(2016) 333 ELT 3 (SC)") from AI HTML, leaving the surrounding argument intact.
     */
    public static String removeCitationText(String html, String caseName) {
        if (html == null || html.isBlank() || caseName == null || caseName.isBlank()) return html;
        String name = Pattern.quote(caseName.trim()).replace(" ", "\\E\\s+\\Q");
        // optional "(2016) 333 ELT 3 (SC)" / "[2019] 10 SCC 1": year, then volume REPORTER page, then (court)
        String reporter = "(?:\\s*,?\\s*(?:\\[|\\()(?:19|20)\\d{2}(?:\\]|\\))"
                + "(?:\\s+\\d+\\s+[A-Z][A-Za-z.]*(?:\\s+[A-Z][A-Za-z.]*)?\\s+\\d+)?"
                + "(?:\\s*\\((?:SC|[A-Z][A-Za-z .]{1,30})\\))?)?";
        String cleaned = html.replaceAll("(?i)(?:\\s*(?:see|reference|refer|precedent|relying on|in)\\s*:?\\s*)?"
                + name + reporter, "");
        return cleaned
                .replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\s+([.,;:])", "$1")
                .replaceAll("([.;:])\\s*[.;:,]+", "$1")
                .replaceAll(" {2,}", " ")
                .replaceAll("<p>\\s*[—–-]?\\s*</p>", "")
                .replaceAll("<li>\\s*</li>", "");
    }
}
