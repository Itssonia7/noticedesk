package com.noticedesk.api.model.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LegalChunk(
        UUID chunkId,
        String actOrCircular,
        String sectionOrPara,
        String title,
        int chunkIndex,
        String content,
        int tokenCount,
        List<Double> embedding,
        OffsetDateTime createdAt,
        Double similarityScore,
        OffsetDateTime savedAt,
        OffsetDateTime effectiveDate,
        OffsetDateTime expiresAt
) {
    public LegalChunk(
            UUID chunkId,
            String actOrCircular,
            String sectionOrPara,
            String title,
            int chunkIndex,
            String content,
            int tokenCount,
            List<Double> embedding,
            OffsetDateTime createdAt,
            Double similarityScore
    ) {
        this(chunkId, actOrCircular, sectionOrPara, title, chunkIndex, content, tokenCount, embedding, createdAt, similarityScore, createdAt, null, null);
    }

    public LegalChunk withSimilarityScore(double score) {
        return new LegalChunk(
                chunkId, actOrCircular, sectionOrPara, title,
                chunkIndex, content, tokenCount, embedding,
                createdAt, score, savedAt, effectiveDate, expiresAt
        );
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }
}
