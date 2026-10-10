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
        OffsetDateTime createdAt
) {}
