package com.noticedesk.api.model.matching;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public record StageTemplate(
        String stage,
        int version,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        Map<String, String> sections,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt
) {}
