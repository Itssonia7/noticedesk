package com.noticedesk.api.service.rag;

import com.noticedesk.api.model.rag.*;
import com.noticedesk.api.service.embedding.EmbeddingProvider;
import com.noticedesk.api.service.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagStoreService {

    private final NamedParameterJdbcTemplate jdbc;
    private final EmbeddingService          embeddingService;
    private final EmbeddingProvider         embeddingProvider;

    private static final List<EvidenceChunk> IN_MEMORY_EVIDENCE_STORE = new CopyOnWriteArrayList<>();
    private static final List<LegalChunk>    IN_MEMORY_LEGAL_STORE    = new CopyOnWriteArrayList<>();

    public static final String GOLD_STANDARD_GSTR2A_TEMPLATE =
            "GOLD-STANDARD TEMPLATE GUIDANCE (FY 2017-18 / 2018-19 ITC Mismatch Defense):\n" +
            "1. Synopsis: State demand under Section 73 is misconceived as GSTR-2A was a dynamic view statement.\n" +
            "2. Primary Ground: Section 16(2) conditions (invoice possession, receipt of goods, payment) fully satisfied.\n" +
            "3. CBIC Circular 183/15/2022-GST: Mismatches are verification triggers, not automatic reversal grounds.\n" +
            "4. Prospective Application: Rule 36(4) matching restrictions apply prospectively from 9 Oct 2019.\n" +
            "5. Judicial Precedents: Cite Calcutta HC Suncraft Energy (2023) and Madras HC D.Y. Beathel (2021) " +
            "confirming department must first proceed against defaulting supplier before reversing buyer ITC.";

    public List<EvidenceChunk> indexEvidenceDocument(
            UUID tenantId,
            UUID matterId,
            UUID documentId,
            String filename,
            String content,
            String documentType) {

        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> chunks = embeddingService.chunkText(content, 500, 50);
        List<List<Double>> vectors = embeddingProvider.embedDocuments(chunks);
        List<EvidenceChunk> created = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            List<Double> vector = vectors.get(i);

            EvidenceChunk chunk = new EvidenceChunk(
                    UUID.randomUUID(), tenantId, matterId, documentId,
                    documentType, filename, i, chunkText,
                    embeddingService.estimateTokens(chunkText),
                    vector, OffsetDateTime.now(), null
            );
            created.add(chunk);
            IN_MEMORY_EVIDENCE_STORE.add(chunk);

            if (jdbc != null) {
                try {
                    String vecStr = vector != null ? "[" + String.join(",", vector.stream().map(Object::toString).toList()) + "]" : null;
                    jdbc.update(
                            """
                            INSERT INTO evidence_chunks (
                                chunk_id, tenant_id, matter_id, document_id, document_type,
                                filename, chunk_index, content, token_count, embedding
                            ) VALUES (
                                :chunk_id, :tenant_id, :matter_id, :document_id, :document_type,
                                :filename, :chunk_index, :content, :token_count, :embedding::vector
                            )
                            """,
                            new MapSqlParameterSource()
                                    .addValue("chunk_id", chunk.chunkId())
                                    .addValue("tenant_id", tenantId)
                                    .addValue("matter_id", matterId)
                                    .addValue("document_id", documentId)
                                    .addValue("document_type", documentType)
                                    .addValue("filename", filename)
                                    .addValue("chunk_index", i)
                                    .addValue("content", chunkText)
                                    .addValue("token_count", chunk.tokenCount())
                                    .addValue("embedding", vecStr)
                    );
                } catch (Exception e) {
                    log.debug("Database evidence chunk insert skipped or deferred: {}", e.getMessage());
                }
            }
        }
        return created;
    }

    public LegalChunk indexLegalChunk(
            String actOrCircular,
            String sectionOrPara,
            String title,
            String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        List<Double> vector = embeddingProvider.embedQuery(content);
        LegalChunk chunk = new LegalChunk(
                UUID.randomUUID(), actOrCircular, sectionOrPara, title,
                0, content, embeddingService.estimateTokens(content),
                vector, OffsetDateTime.now(), null
        );
        IN_MEMORY_LEGAL_STORE.add(chunk);

        if (jdbc != null) {
            try {
                String vecStr = vector != null ? "[" + String.join(",", vector.stream().map(Object::toString).toList()) + "]" : null;
                jdbc.update(
                        """
                        INSERT INTO legal_chunks (
                            chunk_id, act_or_circular, section_or_para, title,
                            chunk_index, content, token_count, embedding
                        ) VALUES (
                            :chunk_id, :act_or_circular, :section_or_para, :title,
                            :chunk_index, :content, :token_count, :embedding::vector
                        )
                        """,
                        new MapSqlParameterSource()
                                .addValue("chunk_id", chunk.chunkId())
                                .addValue("act_or_circular", actOrCircular)
                                .addValue("section_or_para", sectionOrPara)
                                .addValue("title", title)
                                .addValue("chunk_index", 0)
                                .addValue("content", content)
                                .addValue("token_count", chunk.tokenCount())
                                .addValue("embedding", vecStr)
                );
            } catch (Exception e) {
                log.debug("Database legal chunk insert skipped or deferred: {}", e.getMessage());
            }
        }
        return chunk;
    }

    public List<EvidenceChunk> searchEvidence(UUID matterId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<Double> qvec = embeddingProvider.embedQuery(query);

        if (jdbc != null) {
            try {
                String vecStr = "[" + String.join(",", qvec.stream().map(Object::toString).toList()) + "]";
                List<EvidenceChunk> dbResults = jdbc.query(
                        """
                        SELECT chunk_id, tenant_id, matter_id, document_id, document_type,
                               filename, chunk_index, content, token_count,
                               1 - (embedding <=> :qvec::vector) AS similarity_score
                        FROM evidence_chunks
                        WHERE (:matter_id IS NULL OR matter_id = :matter_id)
                        ORDER BY embedding <=> :qvec::vector ASC
                        LIMIT :top_k
                        """,
                        new MapSqlParameterSource()
                                .addValue("qvec", vecStr)
                                .addValue("matter_id", matterId)
                                .addValue("top_k", topK),
                        (rs, rowNum) -> new EvidenceChunk(
                                UUID.fromString(rs.getString("chunk_id")),
                                rs.getString("tenant_id") != null ? UUID.fromString(rs.getString("tenant_id")) : null,
                                rs.getString("matter_id") != null ? UUID.fromString(rs.getString("matter_id")) : null,
                                rs.getString("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null,
                                rs.getString("document_type"),
                                rs.getString("filename"),
                                rs.getInt("chunk_index"),
                                rs.getString("content"),
                                rs.getInt("token_count"),
                                null,
                                null,
                                rs.getDouble("similarity_score")
                        )
                );
                if (!dbResults.isEmpty()) {
                    return dbResults;
                }
            } catch (Exception e) {
                log.debug("Database evidence search skipped or deferred: {}", e.getMessage());
            }
        }

        List<EvidenceChunk> candidates = IN_MEMORY_EVIDENCE_STORE.stream()
                .filter(c -> matterId == null || Objects.equals(c.matterId(), matterId))
                .toList();

        List<EvidenceChunk> scored = new ArrayList<>();
        for (EvidenceChunk c : candidates) {
            if (c.embedding() != null) {
                double score = cosineSimilarity(qvec, c.embedding());
                scored.add(c.withSimilarityScore(Math.round(score * 10000.0) / 10000.0));
            }
        }
        scored.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
        return scored.stream().limit(topK).toList();
    }

    public List<LegalChunk> searchLegal(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<Double> qvec = embeddingProvider.embedQuery(query);

        if (jdbc != null) {
            try {
                String vecStr = "[" + String.join(",", qvec.stream().map(Object::toString).toList()) + "]";
                List<LegalChunk> dbResults = jdbc.query(
                        """
                        SELECT chunk_id, act_or_circular, section_or_para, title,
                               chunk_index, content, token_count,
                               1 - (embedding <=> :qvec::vector) AS similarity_score
                        FROM legal_chunks
                        ORDER BY embedding <=> :qvec::vector ASC
                        LIMIT :top_k
                        """,
                        new MapSqlParameterSource()
                                .addValue("qvec", vecStr)
                                .addValue("top_k", topK),
                        (rs, rowNum) -> new LegalChunk(
                                UUID.fromString(rs.getString("chunk_id")),
                                rs.getString("act_or_circular"),
                                rs.getString("section_or_para"),
                                rs.getString("title"),
                                rs.getInt("chunk_index"),
                                rs.getString("content"),
                                rs.getInt("token_count"),
                                null,
                                null,
                                rs.getDouble("similarity_score")
                        )
                );
                if (!dbResults.isEmpty()) {
                    return dbResults;
                }
            } catch (Exception e) {
                log.debug("Database legal search skipped or deferred: {}", e.getMessage());
            }
        }

        List<LegalChunk> scored = new ArrayList<>();
        for (LegalChunk c : IN_MEMORY_LEGAL_STORE) {
            if (c.embedding() != null) {
                double score = cosineSimilarity(qvec, c.embedding());
                scored.add(c.withSimilarityScore(Math.round(score * 10000.0) / 10000.0));
            }
        }
        scored.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
        return scored.stream().limit(topK).toList();
    }

    public RagContextBundle getRagContext(UUID matterId, String query, int topEvidence, int topLegal) {
        List<EvidenceChunk> evChunks = searchEvidence(matterId, query, topEvidence);
        List<LegalChunk> legChunks = searchLegal(query, topLegal);

        int totalTokens = evChunks.stream().mapToInt(EvidenceChunk::tokenCount).sum() +
                legChunks.stream().mapToInt(LegalChunk::tokenCount).sum();

        return new RagContextBundle(evChunks, legChunks, totalTokens);
    }

    public ConfidenceAssessment evaluateCascadeConfidence(RagContextBundle ragContext) {
        if (ragContext == null) {
            return new ConfidenceAssessment(0.0, false, "NOVEL_CASE", null);
        }

        double maxScore = 0.0;
        for (LegalChunk c : ragContext.legalChunks()) {
            if (c.similarityScore() != null && c.similarityScore() > maxScore) {
                maxScore = c.similarityScore();
            }
        }
        for (EvidenceChunk c : ragContext.evidenceChunks()) {
            if (c.similarityScore() != null && c.similarityScore() > maxScore) {
                maxScore = c.similarityScore();
            }
        }

        double roundedScore = Math.round(maxScore * 10000.0) / 10000.0;

        if (maxScore >= 0.70) {
            return new ConfidenceAssessment(roundedScore, true, "HIGH_MATCH", GOLD_STANDARD_GSTR2A_TEMPLATE);
        } else if (maxScore >= 0.40) {
            return new ConfidenceAssessment(roundedScore, false, "MEDIUM_MATCH", null);
        } else {
            return new ConfidenceAssessment(roundedScore, false, "NOVEL_CASE", null);
        }
    }

    public static void clearInMemoryStores() {
        IN_MEMORY_EVIDENCE_STORE.clear();
        IN_MEMORY_LEGAL_STORE.clear();
    }

    private double cosineSimilarity(List<Double> vecA, List<Double> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size()) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vecA.size(); i++) {
            double a = vecA.get(i);
            double b = vecB.get(i);
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
