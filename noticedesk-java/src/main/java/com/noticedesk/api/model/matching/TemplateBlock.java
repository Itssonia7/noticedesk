package com.noticedesk.api.model.matching;

import java.util.List;

public record TemplateBlock(
        String id,
        int section,
        String html,
        List<String> citations,
        String when
) {}
