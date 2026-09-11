package com.noticedesk.api.model.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EvidenceChunk(
        UUID chunkId,
        UUID tenantId,
        UUID matterId,
        UUID documentId,
        String documentType,
        String filename,
        int chunkIndex,
        String content,
        int tokenCount,
        List<Double> embedding,
        OffsetDateTime createdAt,
        Double similarityScore
) {
    public EvidenceChunk withSimilarityScore(double score) {
        return new EvidenceChunk(
                chunkId, tenantId, matterId, documentId, documentType,
                filename, chunkIndex, content, tokenCount, embedding,
                createdAt, score
        );
    }
}
