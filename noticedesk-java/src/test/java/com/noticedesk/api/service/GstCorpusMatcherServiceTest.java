package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.testsupport.SyntheticCorpusFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against the synthetic corpus of {@link SyntheticCorpusFixture}; the real corpus is never in the repository. */
class GstCorpusMatcherServiceTest {

    @TempDir
    Path corpusDir;

    private GstCorpusMatcherService corpusMatcherService;

    @BeforeEach
    void setUp() throws IOException {
        AppProperties properties = new AppProperties();
        properties.getDrafting().setGstCorpusDir(SyntheticCorpusFixture.create(corpusDir).toString());
        ObjectMapper objectMapper = new ObjectMapper();
        corpusMatcherService = new GstCorpusMatcherService(properties, objectMapper);
        corpusMatcherService.init();
    }

    @Test
    void testMatchNoticeReturnsCorpusResult() {
        Map<String, Object> noticeData = Map.of(
                "document_type", "scn_73",
                "notice_number", "SCN/GST/2026/001",
                "authority", "Proper Officer"
        );
        String ocr = "Notice under section 73 for ITC mismatch GSTR-2A vs GSTR-3B";

        Optional<GstCorpusMatcherService.CorpusMatchResult> resultOpt =
                corpusMatcherService.matchNotice(noticeData, ocr);

        assertTrue(resultOpt.isPresent(), "Corpus match result should be present");
        GstCorpusMatcherService.CorpusMatchResult result = resultOpt.get();

        assertNotNull(result.serial());
        assertNotNull(result.noticeKind());
        assertNotNull(result.strategy());
        assertTrue(result.matchScore() > 0.0);
    }

    @Test
    void testProceduralNoticeFastTrackStrategy() {
        Map<String, Object> noticeData = Map.of(
                "document_type", "scn_73",
                "notice_number", "SCN/2026/001"
        );
        String ocr = "Show Cause Notice under Section 73 for ITC mismatch";

        Optional<GstCorpusMatcherService.CorpusMatchResult> resultOpt =
                corpusMatcherService.matchNotice(noticeData, ocr);

        assertTrue(resultOpt.isPresent());
        assertEquals(GstCorpusMatcherService.Strategy.FAST_TRACK, resultOpt.get().strategy());
    }
}
