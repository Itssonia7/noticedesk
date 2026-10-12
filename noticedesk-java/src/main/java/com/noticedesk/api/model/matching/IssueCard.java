package com.noticedesk.api.model.matching;

import java.time.OffsetDateTime;
import java.util.List;

public record IssueCard(
        String cardId,
        int version,
        String title,
        String summary,
        List<String> sections,
        String periodFrom,
        String periodTo,
        List<String> stages,
        List<String> keywords,
        List<String> notThisIf,
        String templateId,
        String status,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt,
        /** The AI issue section this card was promoted from (null for a CA-written card). */
        String sourceAiSectionId
) {
    public IssueCard(String cardId, int version, String title, String summary, List<String> sections,
                     String periodFrom, String periodTo, List<String> stages, List<String> keywords,
                     List<String> notThisIf, String templateId, String status, String reviewedBy,
                     OffsetDateTime reviewedAt, OffsetDateTime createdAt) {
        this(cardId, version, title, summary, sections, periodFrom, periodTo, stages, keywords, notThisIf,
                templateId, status, reviewedBy, reviewedAt, createdAt, null);
    }

    /** True for a card promoted from an AI issue section (provisional content, see {@code catalogue.include-provisional}). */
    public boolean isProvisional() {
        return sourceAiSectionId != null && !sourceAiSectionId.isBlank();
    }
}
