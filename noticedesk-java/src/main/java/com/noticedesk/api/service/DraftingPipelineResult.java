package com.noticedesk.api.service;

import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.agent.NewIssueDraftingAgent.AiIssueSectionRecord;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.SourceMapEntry;

import java.util.List;
import java.util.Map;

public record DraftingPipelineResult(
        GeneratedDraft draft,
        List<VerifiedCitation> citations,
        Map<String, Object> citationSummary,
        int ragHitCount,
        int diskCacheHitCount,
        int opusCallCount,
        int totalInputTokens,
        int totalOutputTokens,
        String stopReason,
        List<SourceMapEntry> paragraphSourceMap,
        List<DraftCheckResult> checkResults,
        List<AiIssueSectionRecord> aiIssueSections
) {
    public DraftingPipelineResult(
            GeneratedDraft draft,
            List<VerifiedCitation> citations,
            Map<String, Object> citationSummary,
            int ragHitCount,
            int diskCacheHitCount,
            int opusCallCount,
            int totalInputTokens,
            int totalOutputTokens,
            String stopReason,
            List<SourceMapEntry> paragraphSourceMap,
            List<DraftCheckResult> checkResults
    ) {
        this(draft, citations, citationSummary, ragHitCount, diskCacheHitCount, opusCallCount,
                totalInputTokens, totalOutputTokens, stopReason, paragraphSourceMap, checkResults, List.of());
    }

    public DraftingPipelineResult(
            GeneratedDraft draft,
            List<VerifiedCitation> citations,
            Map<String, Object> citationSummary,
            int ragHitCount,
            int diskCacheHitCount,
            int opusCallCount,
            int totalInputTokens,
            int totalOutputTokens,
            String stopReason
    ) {
        this(draft, citations, citationSummary, ragHitCount, diskCacheHitCount, opusCallCount,
                totalInputTokens, totalOutputTokens, stopReason, List.of(), List.of(), List.of());
    }
}
