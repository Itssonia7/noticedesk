package com.noticedesk.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.CitationVerificationResult;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.ExtractedIssue;
import com.noticedesk.api.agent.DraftingAgent.ExtractionResult;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.rag.LegalChunk;
import com.noticedesk.api.service.rag.RagStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Orchestration service for the 5-step legal draft pipeline:
 * 1. Issue Extraction
 * 2. 3-Tier Per-Issue Lookup (RAG -> Disk Cache -> Opus)
 * 3. Deduplication & RAG Auto-Caching
 * 4. 15-Section Draft Generation & Contract Enforcement
 * 5. Citation Verification
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftingPipelineService {

    private static final Path DISK_CACHE_PATH = Paths.get(".opus_cache.json");

    private final DraftingAgent draftingAgent;
    private final RagStoreService ragStoreService;
    private final CitationVerificationAgent citationVerificationAgent;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public DraftingPipelineResult runPipeline(DraftingInput input, boolean enableDiskCache) {
        log.info("DraftingPipelineService starting 5-step pipeline execution (enableDiskCache={})", enableDiskCache);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        // Step 1: Issue Extraction via Claude Haiku
        ExtractionResult extractionResult = draftingAgent.extractIssues(input);
        log.info("Step 1 complete: extracted {} issues (inputTokens={} outputTokens={})",
                extractionResult.issues().size(), extractionResult.inputTokens(), extractionResult.outputTokens());
        totalInputTokens += (extractionResult.inputTokens() != null ? extractionResult.inputTokens() : 0);
        totalOutputTokens += (extractionResult.outputTokens() != null ? extractionResult.outputTokens() : 0);

        // Step 2 & 3: 3-Tier Per-Issue Lookup (1. RAG -> 2. Disk Cache -> 3. Opus)
        double similarityThreshold = properties.getEmbedding() != null ? properties.getEmbedding().getSimilarityThreshold() : properties.getDrafting().getSimilarityThreshold();
        Set<String> uniqueChunkContents = new LinkedHashSet<>();

        int ragHitCount = 0;
        int diskCacheHitCount = 0;
        int opusCallCount = 0;

        Map<String, String> diskCacheMap = enableDiskCache ? loadDiskCache() : new HashMap<>();
        String citationProvider = resolveCitationProvider();
        String expiredPolicy = properties.getDrafting() != null ? properties.getDrafting().getExpiredChunkPolicy() : "FLAG_AND_USE";
        String failPolicy = properties.getDrafting() != null ? properties.getDrafting().getCitationFailPolicy() : "FLAG_AND_CONTINUE";
        List<CitationVerificationAgent.VerifiedCitation> chunkReverifiedCitations = new ArrayList<>();

        for (ExtractedIssue issue : extractionResult.issues()) {
            String query = issue.title() + " " + issue.description();

            // Tier 1: RAG Search First
            List<LegalChunk> searchResults = ragStoreService.searchLegal(query, 1);
            if (!searchResults.isEmpty()) {
                LegalChunk match = searchResults.get(0);
                boolean isHit = match.similarityScore() != null && match.similarityScore() >= similarityThreshold;
                log.info("Issue '{}': RAG SEARCH RESULT -> isHit={} (chunkId={} score={} actOrCircular='{}' title='{}')",
                        issue.title(), isHit, match.chunkId(), match.similarityScore(), match.actOrCircular(), match.title());
                
                if (isHit) {
                    if (match.isExpired() && "EXCLUDE".equalsIgnoreCase(expiredPolicy)) {
                        log.warn("Issue '{}': RAG MATCH chunkId={} EXPIRED on {}. Policy is EXCLUDE -> skipping match to trigger Opus fallback.",
                                issue.title(), match.chunkId(), match.expiresAt());
                    } else {
                        // Citation Re-Check on Retrieved Chunk Content
                        CitationVerificationResult chunkCitationResult = citationVerificationAgent.verify(match.content(), citationProvider);
                        if (chunkCitationResult != null && chunkCitationResult.citations() != null) {
                            chunkReverifiedCitations.addAll(chunkCitationResult.citations());
                        }

                        boolean hasInvalidCitations = chunkCitationResult != null 
                                && chunkCitationResult.citations() != null 
                                && chunkCitationResult.citations().stream().anyMatch(c -> "UNVERIFIED".equalsIgnoreCase(c.status()) || "flagged".equalsIgnoreCase(c.actionTaken()));

                        if (hasInvalidCitations && "FALL_THROUGH_TO_OPUS".equalsIgnoreCase(failPolicy)) {
                            log.warn("Issue '{}': RAG MATCH chunkId={} has invalid citations. Policy is FALL_THROUGH_TO_OPUS -> falling through to Opus.",
                                    issue.title(), match.chunkId());
                        } else {
                            ragHitCount++;
                            uniqueChunkContents.add(match.content());
                            continue;
                        }
                    }
                }
            }

            // Tier 2: Disk Cache Next (if enabled)
            String cacheKey = normalizeKey(issue.title());
            if (enableDiskCache && diskCacheMap.containsKey(cacheKey)) {
                String cachedChunk = diskCacheMap.get(cacheKey);
                // Citation Re-Check on Disk Cache Chunk Content
                CitationVerificationResult chunkCitationResult = citationVerificationAgent.verify(cachedChunk, citationProvider);
                if (chunkCitationResult != null && chunkCitationResult.citations() != null) {
                    chunkReverifiedCitations.addAll(chunkCitationResult.citations());
                }

                boolean hasInvalidCitations = chunkCitationResult != null 
                        && chunkCitationResult.citations() != null 
                        && chunkCitationResult.citations().stream().anyMatch(c -> "UNVERIFIED".equalsIgnoreCase(c.status()) || "flagged".equalsIgnoreCase(c.actionTaken()));

                if (hasInvalidCitations && "FALL_THROUGH_TO_OPUS".equalsIgnoreCase(failPolicy)) {
                    log.warn("Issue '{}': DISK CACHE chunk has invalid citations. Policy is FALL_THROUGH_TO_OPUS -> falling through to Opus.",
                            issue.title());
                } else {
                    log.info("Issue '{}': DISK CACHE HIT from .opus_cache.json", issue.title());
                    diskCacheHitCount++;
                    uniqueChunkContents.add(cachedChunk);
                    ragStoreService.saveNewChunk(issue.title(), issue.description(), cachedChunk);
                    continue;
                }
            }

            // Tier 3: Opus Call
            log.info("Issue '{}': UNMATCHED (RAG miss & Disk Cache miss). Calling Opus...", issue.title());
            opusCallCount++;
            DraftingAgent.OpusTemplateResult opusRes = draftingAgent.generateOpusTemplateResultForUnmatchedIssue(issue, input);
            totalInputTokens += (opusRes.inputTokens() != null ? opusRes.inputTokens() : 0);
            totalOutputTokens += (opusRes.outputTokens() != null ? opusRes.outputTokens() : 0);
            String opusTemplate = opusRes.content();
            LegalChunk savedChunk = ragStoreService.saveNewChunk(issue.title(), issue.description(), opusTemplate);
            if (savedChunk != null) {
                log.info("Auto-cached new Opus chunk into RAG legal_chunks: chunkId={} actOrCircular='{}' title='{}'",
                        savedChunk.chunkId(), savedChunk.actOrCircular(), savedChunk.title());
            }
            uniqueChunkContents.add(opusTemplate);

            if (enableDiskCache) {
                diskCacheMap.put(cacheKey, opusTemplate);
                saveDiskCache(diskCacheMap);
            }
        }

        if (uniqueChunkContents.isEmpty()) {
            uniqueChunkContents.add("General Statutory Ground under Tax Law Notice Response");
        }

        log.info("3-Tier Lookup Summary: rag_hit={} disk_cache_hit={} opus_call={}",
                ragHitCount, diskCacheHitCount, opusCallCount);

        // Step 4: Merge & Format into 15-Section Draft via Claude Haiku
        GeneratedDraft generated = draftingAgent.mergeAndFormatDraft(input, new ArrayList<>(uniqueChunkContents));

        totalInputTokens += (generated.inputTokens() != null ? generated.inputTokens() : 0);
        totalOutputTokens += (generated.outputTokens() != null ? generated.outputTokens() : 0);

        // Step 5: Verify citations
        String allHtml = generated.sections().stream()
                .map(DraftSection::bodyHtml)
                .reduce("", (a, b) -> a + "\n" + b);

        CitationVerificationResult citationResult = citationVerificationAgent.verify(allHtml, citationProvider);
        List<CitationVerificationAgent.VerifiedCitation> allVerifiedCitations = new ArrayList<>(chunkReverifiedCitations);
        if (citationResult.citations() != null) {
            allVerifiedCitations.addAll(citationResult.citations());
        }

        return new DraftingPipelineResult(
                generated,
                allVerifiedCitations,
                citationResult.summary(),
                ragHitCount,
                diskCacheHitCount,
                opusCallCount,
                totalInputTokens,
                totalOutputTokens,
                "end_turn"
        );
    }

    private String normalizeKey(String key) {
        return key != null ? key.trim().toLowerCase() : "";
    }

    private synchronized Map<String, String> loadDiskCache() {
        if (!Files.exists(DISK_CACHE_PATH)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(DISK_CACHE_PATH, StandardCharsets.UTF_8);
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {});
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            log.warn("Failed to load .opus_cache.json disk cache: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private synchronized void saveDiskCache(Map<String, String> cacheMap) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cacheMap);
            Files.writeString(DISK_CACHE_PATH, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to save .opus_cache.json disk cache: {}", e.getMessage());
        }
    }

    private String resolveCitationProvider() {
        String env = System.getenv("CITATION_PROVIDER");
        if (env != null && !env.isBlank()) return env;
        String configured = properties.getDocumentParsing().getCitationProvider();
        return (configured != null && !configured.isBlank()) ? configured : "stub";
    }
}
