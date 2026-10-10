package com.noticedesk.api.model.matching;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ReplyTemplate(
        String templateId,
        int version,
        String cardId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        List<TemplateBlock> blocks,
        List<String> variables,
        List<String> documents,
        String summaryLine,
        List<String> proceduralObjectionOptions,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt
) {}
