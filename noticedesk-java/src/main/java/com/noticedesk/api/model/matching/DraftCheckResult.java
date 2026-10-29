package com.noticedesk.api.model.matching;

import java.util.Map;

public record DraftCheckResult(
        String checkName,
        String severity, // "block", "warn", "info"
        boolean passed,
        Map<String, Object> details
) {}
