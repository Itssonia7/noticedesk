package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * GST Corpus Matcher Service.
 *
 * Automatically indexes the NoticeDesk GST Corpus manifest (pairing_manifest.jsonl)
 * and matches incoming GST notices to the closest corpus pair. Classifies whether
 * the notice can be Fast-Tracked (direct template substitution) or handled via Hybrid
 * drafting (LLM augmented with corpus legal arguments).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstCorpusMatcherService {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public enum Strategy {
        FAST_TRACK,
        HYBRID
    }

    @Getter
    public static class CorpusItem {
        private final String serial;
        private final String draftFile;
        private final String noticeFile;
        private final String noticeKind;
        private final String draftType;

        public CorpusItem(String serial, String draftFile, String noticeFile, String noticeKind, String draftType) {
            this.serial = serial;
            this.draftFile = draftFile;
            this.noticeFile = noticeFile;
            this.noticeKind = noticeKind;
            this.draftType = draftType;
        }
    }

    public record CorpusMatchResult(
            String serial,
            String noticeKind,
            String draftFile,
            String noticeFile,
            Strategy strategy,
            double matchScore,
            Path draftPath
    ) {}

    private final List<CorpusItem> corpusItems = new ArrayList<>();
    private Path resolvedCorpusDir;

    @PostConstruct
    public void init() {
        String configuredDir = properties.getDrafting().getGstCorpusDir();
        Path candidatePath = Paths.get(configuredDir).toAbsolutePath().normalize();

        if (!Files.exists(candidatePath)) {
            // Fallback candidate search relative to working directory
            Path parentFallback = Paths.get("../NoticeDesk_GST_Corpus_1300_Paired/NoticeDesk_GST_Corpus_1-300_Paired").toAbsolutePath().normalize();
            if (Files.exists(parentFallback)) {
                candidatePath = parentFallback;
            }
        }

        resolvedCorpusDir = candidatePath;
        Path manifestPath = resolvedCorpusDir.resolve("pairing_manifest.jsonl");

        if (!Files.exists(manifestPath)) {
            log.warn("GST Corpus manifest not found at {}. Matcher will run in fallback mode.", manifestPath);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(manifestPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JsonNode node = objectMapper.readTree(line);
                CorpusItem item = new CorpusItem(
                        node.path("serial").asText(""),
                        node.path("draft_file").asText(""),
                        node.path("notice_file").asText(""),
                        node.path("notice_kind").asText(""),
                        node.path("draft_type").asText("")
                );
                corpusItems.add(item);
            }
            log.info("Successfully indexed {} GST Corpus document pairs from {}", corpusItems.size(), manifestPath);
        } catch (IOException e) {
            log.error("Failed to load GST Corpus manifest from {}", manifestPath, e);
        }
    }

    /**
     * Automatically matches an incoming notice against indexed corpus pairs.
     */
    public Optional<CorpusMatchResult> matchNotice(Map<String, Object> noticeData, String noticeOcrExcerpt) {
        if (corpusItems.isEmpty()) {
            return Optional.empty();
        }

        String docType = String.valueOf(noticeData.getOrDefault("document_type", "")).toLowerCase();
        String noticeNum = String.valueOf(noticeData.getOrDefault("notice_number", "")).toLowerCase();
        String authority = String.valueOf(noticeData.getOrDefault("authority", "")).toLowerCase();
        String rawOcr = (noticeOcrExcerpt != null ? noticeOcrExcerpt : "").toLowerCase();

        CorpusItem bestMatch = null;
        double bestScore = 0.0;

        for (CorpusItem item : corpusItems) {
            double score = computeMatchScore(item, docType, noticeNum, authority, rawOcr);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = item;
            }
        }

        // Default to first item if score threshold is low
        if (bestMatch == null) {
            bestMatch = corpusItems.get(0);
            bestScore = 0.1;
        }

        Strategy strategy = determineStrategy(bestMatch, docType, rawOcr);
        Path draftPath = resolvedCorpusDir.resolve("drafts").resolve(bestMatch.getDraftFile());

        return Optional.of(new CorpusMatchResult(
                bestMatch.getSerial(),
                bestMatch.getNoticeKind(),
                bestMatch.getDraftFile(),
                bestMatch.getNoticeFile(),
                strategy,
                bestScore,
                draftPath
        ));
    }

    private double computeMatchScore(CorpusItem item, String docType, String noticeNum, String authority, String ocr) {
        double score = 0.0;
        String noticeKind = item.getNoticeKind().toLowerCase();
        String draftFile = item.getDraftFile().toLowerCase();

        // 1. Notice Kind / Document Type match
        if (!docType.isEmpty() && (docType.contains(noticeKind) || noticeKind.contains(docType))) {
            score += 4.0;
        }

        // 2. Form & Section Matches (e.g. DRC01, DRC03, SCN 73, SCN 74, S129, S16(4), REG17, ASMT10)
        String[] keywords = {"drc01", "drc03", "drc07", "asmt10", "asmt13", "reg17", "apl01", "73", "74", "129", "83", "86a", "16(4)", "16(5)", "128a"};
        for (String kw : keywords) {
            if ((docType.contains(kw) || noticeNum.contains(kw) || ocr.contains(kw)) && draftFile.contains(kw)) {
                score += 1.5;
            }
        }

        return score;
    }

    private Strategy determineStrategy(CorpusItem item, String docType, String ocr) {
        String noticeKind = item.getNoticeKind().toLowerCase();
        String draftType = item.getDraftType().toLowerCase();

        // Standard procedural/administrative intimations can be Fast-Tracked
        if ("system_intimation".equals(noticeKind) ||
            "letter".equals(draftType) ||
            ocr.contains("adjournment") ||
            ocr.contains("without din") ||
            ocr.contains("request for reasoned order")) {
            return Strategy.FAST_TRACK;
        }

        // Substantive tax demands use Hybrid (LLM + Corpus reference draft context)
        return Strategy.HYBRID;
    }
}
