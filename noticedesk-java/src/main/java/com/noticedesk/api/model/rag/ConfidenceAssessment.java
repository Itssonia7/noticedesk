package com.noticedesk.api.model.rag;

public record ConfidenceAssessment(
        double confidenceScore,
        boolean isHighConfidence,
        String matchTier,
        String guidedTemplate,
        String draftingModel
) {
    public ConfidenceAssessment(double confidenceScore, boolean isHighConfidence, String matchTier, String guidedTemplate) {
        this(confidenceScore, isHighConfidence, matchTier, guidedTemplate, "claude-opus-4-7");
    }
}
