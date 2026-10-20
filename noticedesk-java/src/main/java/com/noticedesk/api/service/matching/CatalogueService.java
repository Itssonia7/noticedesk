package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogueService {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Loads the cards matching may see, deterministically sorted by card_id ASC, version DESC (prompt
     * caching stability):
     * <ul>
     *   <li>'active' cards always;</li>
     *   <li>every 'draft' card when {@code matching.allow-draft-cards} is true (dev seeds);</li>
     *   <li>otherwise 'draft' cards promoted from an approved AI issue section (provisional content) only
     *       when {@code catalogue.include-provisional} is true (the default).</li>
     * </ul>
     */
    public List<IssueCard> getActiveCards() {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String sql = """
                SELECT card_id, version, title, summary, sections, period_from, period_to,
                       stages, keywords, not_this_if, template_id, status, reviewed_by, reviewed_at, created_at,
                       source_ai_section_id
                FROM issue_cards
                WHERE status IN ('active', 'draft')
                ORDER BY card_id ASC, version DESC
                """;
        List<IssueCard> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String cardId = rs.getString("card_id");
            int version = rs.getInt("version");
            String title = rs.getString("title");
            String summary = rs.getString("summary");
            List<String> sections = parseJsonList(rs.getString("sections"));
            String periodFrom = rs.getString("period_from");
            String periodTo = rs.getString("period_to");
            List<String> stages = parseJsonList(rs.getString("stages"));
            List<String> keywords = parseJsonList(rs.getString("keywords"));
            List<String> notThisIf = parseJsonList(rs.getString("not_this_if"));
            String templateId = rs.getString("template_id");
            String status = rs.getString("status");
            String reviewedBy = rs.getString("reviewed_by");
            OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
            Object source = rs.getObject("source_ai_section_id");

            return new IssueCard(cardId, version, title, summary, sections, periodFrom, periodTo,
                    stages, keywords, notThisIf, templateId, status, reviewedBy, reviewedAt, createdAt,
                    source != null ? source.toString() : null);
        });
        return visibleCards(rows, allowDraftCards(), includeProvisional());
    }

    /**
     * The visible cards keyed by card id, latest version of each (read access to a card's stages and NOT THIS IF
     * rules for the post-matching stage correction). Empty without a database.
     */
    public Map<String, IssueCard> cardsById() {
        return byId(getActiveCards());
    }

    /** {@code cards} keyed by card id; the first (latest, per the load order) version of each id wins. */
    public static Map<String, IssueCard> byId(List<IssueCard> cards) {
        Map<String, IssueCard> out = new LinkedHashMap<>();
        if (cards == null) return out;
        for (IssueCard c : cards) {
            if (c != null && c.cardId() != null) out.putIfAbsent(c.cardId(), c);
        }
        return out;
    }

    /** {@code matching.allow-draft-cards}: every draft card is visible (development seeds). */
    public boolean allowDraftCards() {
        return properties != null && properties.getMatching() != null && properties.getMatching().isAllowDraftCards();
    }

    /** {@code catalogue.include-provisional}: draft cards promoted from AI sections are visible (default true). */
    public boolean includeProvisional() {
        return properties == null || properties.getCatalogue() == null || properties.getCatalogue().isIncludeProvisional();
    }

    /** The cards of {@code loaded} that matching may see under the two toggles (see {@link #getActiveCards}). */
    public static List<IssueCard> visibleCards(List<IssueCard> loaded, boolean allowDraft, boolean includeProvisional) {
        List<IssueCard> out = new ArrayList<>();
        for (IssueCard c : loaded) {
            if (c == null || c.status() == null) continue;
            if (isVisible(c, allowDraft, includeProvisional)) out.add(c);
        }
        return out;
    }

    static boolean isVisible(IssueCard card, boolean allowDraft, boolean includeProvisional) {
        String status = card.status().trim().toLowerCase(Locale.ROOT);
        if ("active".equals(status)) return true;
        if (!"draft".equals(status)) return false;
        return allowDraft || (includeProvisional && card.isProvisional());
    }

    /** Heading of the block that lists, by title only, the siblings NOT THIS IF rules name but the shortlist does not show. */
    public static final String TITLES_ONLY_HEADING = "OTHER CARDS NAMED BY NOT THIS IF RULES (not shown in full):";

    /**
     * Renders loaded cards into a byte-identical compact text block for Anthropic prompt caching (the full catalogue
     * render: its text is what the catalogue hash is taken of, so it keeps the KEYWORDS line).
     */
    public String renderCatalogueCompactText(List<IssueCard> cards) {
        return render(cards, true);
    }

    /**
     * Renders a shortlist for the matcher: the full cards as {@link #renderCatalogueCompactText} without the KEYWORDS
     * line (the keywords did their work in code), then, when {@code titlesOnly} is not empty, a closing block
     * {@link #TITLES_ONLY_HEADING} with one {@code NDK-xxx | <title>} line per card.
     */
    public String renderShortlistText(List<IssueCard> cards, List<IssueCard> titlesOnly) {
        String body = render(cards, false);
        if (titlesOnly == null || titlesOnly.isEmpty()) return body;
        List<IssueCard> sorted = new ArrayList<>(titlesOnly);
        sorted.sort(Comparator.comparing(IssueCard::cardId));
        StringBuilder sb = new StringBuilder(body).append("\n").append(TITLES_ONLY_HEADING).append("\n");
        for (IssueCard c : sorted) {
            sb.append(c.cardId()).append(" | ").append(c.title() != null ? c.title().replaceAll("\\s+", " ").trim() : "").append("\n");
        }
        return sb.toString();
    }

    private static String render(List<IssueCard> cards, boolean withKeywords) {
        if (cards == null || cards.isEmpty()) {
            return "ISSUE CATALOGUE: Empty";
        }

        List<IssueCard> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparing(IssueCard::cardId).thenComparing(Comparator.comparingInt(IssueCard::version).reversed()));

        StringBuilder sb = new StringBuilder();
        sb.append("=== GST ISSUE CARD CATALOGUE (Total Cards: ").append(sorted.size()).append(") ===\n\n");

        for (IssueCard card : sorted) {
            sb.append("CARD_ID: ").append(card.cardId()).append(" (v").append(card.version()).append(")\n");
            sb.append("TITLE: ").append(card.title()).append("\n");
            sb.append("SUMMARY: ").append(card.summary()).append("\n");
            if (card.periodFrom() != null || card.periodTo() != null) {
                sb.append("PERIOD: ").append(card.periodFrom() != null ? card.periodFrom() : "N/A")
                        .append(" to ").append(card.periodTo() != null ? card.periodTo() : "N/A").append("\n");
            }
            if (card.stages() != null && !card.stages().isEmpty()) {
                sb.append("STAGES: ").append(String.join(", ", card.stages())).append("\n");
            }
            if (withKeywords && card.keywords() != null && !card.keywords().isEmpty()) {
                sb.append("KEYWORDS: ").append(String.join(", ", card.keywords())).append("\n");
            }
            if (card.notThisIf() != null && !card.notThisIf().isEmpty()) {
                sb.append("NOT THIS IF:\n");
                for (String rule : card.notThisIf()) {
                    sb.append("  - ").append(rule).append("\n");
                }
            }
            sb.append("----------------------------------------------------------------\n");
        }

        return sb.toString();
    }

    /**
     * Calculates SHA-256 hash of rendered catalogue text.
     */
    public String getCatalogueHash(String renderedText) {
        if (renderedText == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(renderedText.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(json);
        }
    }
}
