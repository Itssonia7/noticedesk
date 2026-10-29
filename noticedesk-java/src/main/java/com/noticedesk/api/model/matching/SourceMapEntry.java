package com.noticedesk.api.model.matching;

public record SourceMapEntry(
        int paraNo,
        int sectionNum,
        String templateId,
        int templateVersion,
        String blockId
) {}
