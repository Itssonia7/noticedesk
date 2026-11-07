package com.noticedesk.api.service;

import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;

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
        String stopReason
) {}
