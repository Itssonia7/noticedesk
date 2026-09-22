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
        Double similarityScore
) {
    public LegalChunk withSimilarityScore(double score) {
        return new LegalChunk(
                chunkId, actOrCircular, sectionOrPara, title,
                chunkIndex, content, tokenCount, embedding,
                createdAt, score
        );
    }
}
