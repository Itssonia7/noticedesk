package com.noticedesk.api.service.ocr;

import com.noticedesk.api.service.IdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-Layered OCR Quality Gate Service.
 *
 * Evaluates raw OCR text and document characteristics using deterministic Java heuristics
 * to prevent corrupted or handwritten text from causing downstream LLM hallucinations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrQualityGateService {

    public enum QualityStatus {
        HIGH_QUALITY,
        LOW_QUALITY_SCAN
    }

    public record OcrQualityResult(
            QualityStatus status,
            double qualityScore,
            List<String> failureReasons,
            boolean isVisionFallbackRecommended,
            Map<String, Object> metrics
    ) {}

    private static final Pattern GSTIN_PATTERN = Pattern.compile(
            "\\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]\\b", Pattern.CASE_INSENSITIVE);
    
    private static final Pattern PAN_PATTERN = Pattern.compile(
            "\\b[A-Z]{5}[0-9]{4}[A-Z]\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> LEGAL_ANCHOR_KEYWORDS = Set.of(
            "section", "act", "notice", "tax", "period", "drc", "asmt", "demand",
            "gst", "penalty", "interest", "hearing", "rupees", "amount", "registered"
    );

    private final IdentityService identityService;

    public OcrQualityResult assessQuality(String ocrText, Integer pageCount, String ocrProvider) {
        List<String> failureReasons = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (ocrText == null || ocrText.isBlank()) {
            failureReasons.add("Empty or null OCR text returned");
            return new OcrQualityResult(QualityStatus.LOW_QUALITY_SCAN, 0.0, failureReasons, true, Map.of("wordCount", 0));
        }

        String cleanedText = ocrText.strip();
        String lowerText = cleanedText.toLowerCase(Locale.ROOT);
        int pages = (pageCount != null && pageCount > 0) ? pageCount : 1;

        // 1. Text Volume Check
        String[] words = cleanedText.split("\\s+");
        int totalWords = words.length;
        double wordsPerPage = (double) totalWords / pages;
        metrics.put("totalWords", totalWords);
        metrics.put("wordsPerPage", Math.round(wordsPerPage * 100.0) / 100.0);

        double volumeScore = 1.0;
        if (wordsPerPage < 25.0) {
            volumeScore = 0.2;
            failureReasons.add(String.format("Severely low text volume (%.1f words/page, expected >= 40)", wordsPerPage));
        } else if (wordsPerPage < 40.0) {
            volumeScore = 0.6;
            failureReasons.add(String.format("Low text volume (%.1f words/page)", wordsPerPage));
        }

        // 2. Structured Identifier Match (GSTIN / PAN)
        boolean hasGstin = GSTIN_PATTERN.matcher(cleanedText).find();
        boolean hasPan = PAN_PATTERN.matcher(cleanedText).find();
        metrics.put("hasGstin", hasGstin);
        metrics.put("hasPan", hasPan);

        double identifierScore = 1.0;
        if (!hasGstin && !hasPan) {
            identifierScore = 0.4;
            failureReasons.add("No valid GSTIN or PAN identifier pattern recognized in OCR text");
        }

        // 3. Legal Keyword Anchor Density Check
        int anchorHits = 0;
        for (String kw : LEGAL_ANCHOR_KEYWORDS) {
            if (lowerText.contains(kw)) {
                anchorHits++;
            }
        }
        metrics.put("legalAnchorHits", anchorHits);

        double keywordScore = 1.0;
        if (anchorHits < 3) {
            keywordScore = 0.3;
            failureReasons.add(String.format("Low legal keyword density (found %d anchor terms, expected >= 3)", anchorHits));
        } else if (anchorHits < 5) {
            keywordScore = 0.7;
        }

        // 4. Character Noise / Symbol Garbage Ratio Check
        long alphaNumCount = cleanedText.chars().filter(ch -> Character.isLetterOrDigit(ch)).count();
        long symbolCount = cleanedText.chars().filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)).count();
        double totalChars = cleanedText.length();
        double noiseRatio = totalChars > 0 ? (double) symbolCount / totalChars : 0.0;
        metrics.put("symbolNoiseRatio", Math.round(noiseRatio * 10000.0) / 10000.0);

        double noiseScore = 1.0;
        if (noiseRatio > 0.25) {
            noiseScore = 0.2;
            failureReasons.add(String.format("High symbol noise ratio (%.2f%% symbols, possible scan distortion)", noiseRatio * 100.0));
        } else if (noiseRatio > 0.15) {
            noiseScore = 0.7;
        }

        // 5. Composite Quality Score Calculation
        // Weights: Keyword Density (30%), Volume (30%), Identifier (20%), Noise (20%)
        double compositeScore = (keywordScore * 0.30) + (volumeScore * 0.30) + (identifierScore * 0.20) + (noiseScore * 0.20);
        compositeScore = Math.round(compositeScore * 100.0) / 100.0;
        metrics.put("compositeScore", compositeScore);

        QualityStatus status = (compositeScore >= 0.75 && failureReasons.isEmpty()) 
                ? QualityStatus.HIGH_QUALITY 
                : QualityStatus.LOW_QUALITY_SCAN;

        boolean isVisionRecommended = (status == QualityStatus.LOW_QUALITY_SCAN);

        log.info("OcrQualityGate evaluated doc: pages={} words={} compositeScore={} status={} failures={}",
                pages, totalWords, compositeScore, status, failureReasons);

        return new OcrQualityResult(status, compositeScore, failureReasons, isVisionRecommended, metrics);
    }
}
