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
     * Loads cards from issue_cards table according to configuration.
     * If allowDraftCards is true, loads 'active' and 'draft' cards; otherwise loads ONLY 'active' cards.
     * Cards are deterministically sorted by card_id ASC, version DESC to ensure prompt caching stability.
     */
    public List<IssueCard> getActiveCards() {
        boolean allowDraft = properties != null && properties.getMatching() != null && properties.getMatching().isAllowDraftCards();

        String sql = """
                SELECT card_id, version, title, summary, sections, period_from, period_to,
                       stages, keywords, not_this_if, template_id, status, reviewed_by, reviewed_at, created_at
                FROM issue_cards
                WHERE status IN (%s)
                ORDER BY card_id ASC, version DESC
                """.formatted(allowDraft ? "'active', 'draft'" : "'active'");

        if (jdbcTemplate == null) {
            return List.of();
        }

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
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

            return new IssueCard(cardId, version, title, summary, sections, periodFrom, periodTo,
                    stages, keywords, notThisIf, templateId, status, reviewedBy, reviewedAt, createdAt);
        });
    }

    /**
     * Renders loaded cards into a byte-identical compact text block for Anthropic prompt caching.
     */
    public String renderCatalogueCompactText(List<IssueCard> cards) {
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
            if (card.keywords() != null && !card.keywords().isEmpty()) {
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
