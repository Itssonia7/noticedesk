package com.noticedesk.api.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.CatalogueService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared pieces of the opt-in live runners for the private notice set kept outside the repository
 * ({@code $PRIVATE_SEED_DIR/notices/*.json}, see docs/PRIVATE_DATA.md): notice file lookup and the
 * guard against running a private catalogue next to the committed dummy cards. Nothing here prints
 * notice contents.
 */
public final class PrivateSetSupport {

    private PrivateSetSupport() {}

    /** Prefix of the committed dev / dummy issue cards (CARD-001..CARD-020). */
    public static final String DUMMY_CARD_PREFIX = "CARD-";

    /** The "notices" folder of a private seed directory, or {@code null} when the variable is blank. */
    public static File privateNoticesDir(String privateSeedDir) {
        if (privateSeedDir == null || privateSeedDir.isBlank()) return null;
        return new File(privateSeedDir, "notices");
    }

    /** Every {@code *.json} under {@code $PRIVATE_SEED_DIR/notices}, sorted by file name; empty when unset or missing. */
    public static List<Resource> privateNotices(String privateSeedDir) {
        File dir = privateNoticesDir(privateSeedDir);
        List<Resource> out = new ArrayList<>();
        if (dir == null) return out;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) out.add(new FileSystemResource(f));
        return out;
    }

    /**
     * One private notice by file name inside {@code $PRIVATE_SEED_DIR/notices}, or {@code null} when the
     * directory is unset or the file does not exist.
     */
    public static Resource privateNotice(String privateSeedDir, String fileName) {
        File dir = privateNoticesDir(privateSeedDir);
        if (dir == null || fileName == null || fileName.isBlank()) return null;
        File f = new File(dir, fileName.trim());
        return f.isFile() ? new FileSystemResource(f) : null;
    }

    /**
     * Resolves a {@code V7_LIVE_NOTICES} entry: an absolute path, else {@code testbench/notices/<name>} on the
     * classpath, else {@code $PRIVATE_SEED_DIR/notices/<name>}. {@code null} when found nowhere.
     */
    public static Resource resolveNotice(String name, String privateSeedDir) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        File asPath = new File(n);
        if (asPath.isAbsolute()) {
            return asPath.isFile() ? new FileSystemResource(asPath) : null;
        }
        ClassPathResource onClasspath = new ClassPathResource("testbench/notices/" + n);
        if (onClasspath.exists()) return onClasspath;
        return privateNotice(privateSeedDir, n);
    }

    /**
     * One notice JSON of the live runners. {@code answerKey} is null for a real notice (no {@code answer_key});
     * {@code text} is {@code full_ocr_text}, else {@code text}; a blank {@code document_type} is "unknown".
     */
    public record NoticeJson(String noticeId, String documentType, String text, JsonNode answerKey) {
        public boolean realNotice() {
            return answerKey == null;
        }

        /** The "Expected Status (Answer Key)" value: the first issue's status, or "none (real notice)". */
        public String expectedStatus() {
            return realNotice() ? "none (real notice)" : answerKey.path("issues").path(0).path("status").asText("none");
        }
    }

    /**
     * Reads a notice JSON: a bench notice (with {@code answer_key}) or a real notice without one. The minimal real
     * notice is {@code {"notice_id": ..., "document_type": "unknown", "full_ocr_text": ...}} ({@code text} is
     * accepted for {@code full_ocr_text}).
     *
     * @throws IllegalArgumentException when notice_id or the text is missing (the message names the field only)
     */
    public static NoticeJson readNotice(JsonNode root) {
        String id = root != null ? text(root, "notice_id") : null;
        if (id == null || id.isBlank()) throw new IllegalArgumentException("notice JSON has no notice_id");
        String body = text(root, "full_ocr_text");
        if (body == null || body.isBlank()) body = text(root, "text");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("notice " + id + " has no full_ocr_text or text");
        String type = text(root, "document_type");
        JsonNode key = root.get("answer_key");
        return new NoticeJson(id.trim(), type == null || type.isBlank() ? "unknown" : type.trim(), body,
                key == null || key.isNull() || key.isMissingNode() ? null : key);
    }

    /** Optional file under {@code $PRIVATE_SEED_DIR} listing the defect card ids, one per line. */
    public static final String DEFECT_CARDS_FILE = "defect_cards.txt";

    /**
     * The defect card ids from {@code $PRIVATE_SEED_DIR/defect_cards.txt} (one id per line, upper-cased, blank
     * lines and {@code #} comments ignored), or {@code null} when the variable is blank or the file is absent /
     * unreadable (the defect count is then skipped). Nothing from the file is logged.
     */
    public static Set<String> defectCardIds(String privateSeedDir) {
        if (privateSeedDir == null || privateSeedDir.isBlank()) return null;
        File f = new File(privateSeedDir, DEFECT_CARDS_FILE);
        if (!f.isFile()) return null;
        try {
            return parseDefectCardIds(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** The ids in a {@code defect_cards.txt} file's lines: trimmed, upper-cased; blank lines and {@code #} comments skipped. */
    public static Set<String> parseDefectCardIds(List<String> lines) {
        Set<String> ids = new TreeSet<>();
        for (String line : lines) {
            String s = line.trim();
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash).trim();
            if (!s.isEmpty()) ids.add(s.toUpperCase(Locale.ROOT));
        }
        return ids;
    }

    /** Distinct card ids in {@code issue_cards} (any status) that start with {@link #DUMMY_CARD_PREFIX}. */
    public static List<String> dummyCardIds(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT DISTINCT card_id FROM issue_cards WHERE card_id LIKE ? ORDER BY card_id",
                String.class, DUMMY_CARD_PREFIX + "%");
    }

    /**
     * The refusal message when dummy cards sit in the database while a private catalogue is in use
     * ({@code PRIVATE_SEED_DIR} set), else {@code null}. Without {@code PRIVATE_SEED_DIR} the dummy cards are
     * the expected catalogue.
     */
    public static String dummyCardRefusal(List<String> dummyCardIds, String privateSeedDir) {
        if (privateSeedDir == null || privateSeedDir.isBlank()) return null;
        if (dummyCardIds == null || dummyCardIds.isEmpty()) return null;
        return "REFUSED: dummy cards (CARD-*) present in the database alongside a private catalogue; delete them first"
                + " (" + dummyCardIds.size() + " found, e.g. " + dummyCardIds.get(0) + ")";
    }

    /** Runs the dummy-card guard against the database: the refusal message, or {@code null} when the run may go on. */
    public static String dummyCardRefusal(JdbcTemplate jdbc, String privateSeedDir) {
        if (privateSeedDir == null || privateSeedDir.isBlank()) return null;
        return dummyCardRefusal(dummyCardIds(jdbc), privateSeedDir);
    }

    /**
     * The issue cards of a private-seed {@code cards} folder ({@code *.json}, each one card object or an array of
     * them, the {@code issue_cards} column names as {@code PrivateSeedLoader} reads them; status defaults to draft),
     * in the catalogue's load order (card_id ascending, version descending). Read without a database, for the
     * zero-cost shortlist dry run. A file that is not valid JSON or a card without {@code card_id} is reported by
     * file number only, never by name or content.
     */
    public static List<IssueCard> readCards(File cardsDir, ObjectMapper om) {
        List<IssueCard> out = new ArrayList<>();
        File[] files = cardsDir != null ? cardsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json")) : null;
        if (files == null) return out;
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            JsonNode node;
            try {
                node = om.readTree(files[i]);
            } catch (IOException e) {
                throw new IllegalStateException("cards file #" + (i + 1) + " is not valid JSON (" + e.getClass().getSimpleName() + ")");
            }
            List<JsonNode> items = new ArrayList<>();
            if (node.isArray()) node.forEach(items::add);
            else items.add(node);
            for (JsonNode c : items) {
                String id = text(c, "card_id");
                if (id == null || id.isBlank()) throw new IllegalStateException("cards file #" + (i + 1) + " has a card without card_id");
                String status = text(c, "status");
                out.add(new IssueCard(id.trim(), c.path("version").asInt(1), text(c, "title"), text(c, "summary"),
                        list(c, "sections"), text(c, "period_from"), text(c, "period_to"), list(c, "stages"), list(c, "keywords"),
                        list(c, "not_this_if"), text(c, "template_id"), status == null || status.isBlank() ? "draft" : status,
                        null, null, null, text(c, "source_ai_section_id")));
            }
        }
        out.sort(Comparator.comparing(IssueCard::cardId).thenComparing(Comparator.comparingInt(IssueCard::version).reversed()));
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> list(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return List.of();
        List<String> out = new ArrayList<>();
        if (v.isArray()) {
            for (JsonNode e : v) if (!e.isNull()) out.add(e.asText());
        } else {
            out.add(v.asText());
        }
        return List.copyOf(out);
    }

    /** One line with the number of cards matching will see and the catalogue hash the matcher reports. */
    public static String catalogueLine(CatalogueService catalogueService) {
        List<IssueCard> cards = catalogueService.getActiveCards();
        String hash = catalogueService.getCatalogueHash(catalogueService.renderCatalogueCompactText(cards));
        return "Catalogue: " + cards.size() + " card(s) visible to matching, catalogue_hash=" + hash;
    }
}
