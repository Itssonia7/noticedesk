package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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
}
