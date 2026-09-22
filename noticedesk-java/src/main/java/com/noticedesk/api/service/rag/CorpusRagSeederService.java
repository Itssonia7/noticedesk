package com.noticedesk.api.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Corpus RAG Seeder Service.
 *
 * Seeds all 300 GST notice-reply pairs from NoticeDesk_GST_Corpus_1300_Paired into
 * RagStoreService legal_chunks on application startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CorpusRagSeederService {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RagStoreService ragStoreService;

    private final AtomicBoolean seeded = new AtomicBoolean(false);

    @PostConstruct
    public void seedCorpusToRag() {
        if (seeded.get()) {
            return;
        }

        String configuredDir = properties.getDrafting().getGstCorpusDir();
        Path corpusDir = Paths.get(configuredDir).toAbsolutePath().normalize();

        if (!Files.exists(corpusDir)) {
            Path parentFallback = Paths.get("../NoticeDesk_GST_Corpus_1300_Paired/NoticeDesk_GST_Corpus_1-300_Paired").toAbsolutePath().normalize();
            if (Files.exists(parentFallback)) {
                corpusDir = parentFallback;
            }
        }

        Path manifestPath = corpusDir.resolve("pairing_manifest.jsonl");
        Path draftsDir = corpusDir.resolve("drafts");

        if (!Files.exists(manifestPath) || !Files.exists(draftsDir)) {
            log.warn("Corpus manifest or drafts directory not found at {}. Skipping RAG seeding.", corpusDir);
            return;
        }

        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(manifestPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JsonNode node = objectMapper.readTree(line);
                String serial = node.path("serial").asText("");
                String draftFile = node.path("draft_file").asText("");
                String noticeKind = node.path("notice_kind").asText("");

                Path draftPath = draftsDir.resolve(draftFile);
                String draftText = readDraftText(draftPath);

                if (!draftText.isBlank()) {
                    ragStoreService.indexLegalChunk(
                            "GST_CORPUS",
                            serial + " (" + noticeKind + ")",
                            draftFile,
                            draftText
                    );
                    count++;
                }
            }
            seeded.set(true);
            log.info("Successfully seeded {} GST Corpus legal drafts into RagStoreService.", count);
        } catch (Exception e) {
            log.error("Failed during Corpus RAG seeding from {}", corpusDir, e);
        }
    }

    private String readDraftText(Path draftPath) {
        if (!Files.exists(draftPath)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream is = Files.newInputStream(draftPath);
             XWPFDocument doc = new XWPFDocument(is)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("Apache POI read skipped for {}: {}", draftPath.getFileName(), e.getMessage());
        }
        return sb.toString();
    }

    public boolean isSeeded() {
        return seeded.get();
    }
}
