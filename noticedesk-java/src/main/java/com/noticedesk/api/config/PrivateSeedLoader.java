package com.noticedesk.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * Loads the firm's PRIVATE catalogue (real issue cards, reply templates, stage templates) from
 * {@code $PRIVATE_SEED_DIR}, a directory outside the repository. Active only with the
 * {@code private-seed} profile. See docs/PRIVATE_DATA.md.
 *
 * <pre>
 * $PRIVATE_SEED_DIR/cards/*.json            -> issue_cards     (upsert on card_id, version)
 * $PRIVATE_SEED_DIR/templates/*.json        -> reply_templates (upsert on template_id, version)
 * $PRIVATE_SEED_DIR/stage_templates/*.json  -> stage_templates (upsert on stage, version)
 * </pre>
 * Each file holds one JSON object or an array of objects with the table's column names.
 * All files are parsed and validated before anything is written. Logs carry counts and file
 * numbers only, never file names or contents.
 */
@Component
@Profile("private-seed")
@RequiredArgsConstructor
@Slf4j
public class PrivateSeedLoader implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public record SeedCounts(int cards, int templates, int stageTemplates) {}

    private static final String UPSERT_CARD = """
            INSERT INTO issue_cards (card_id, version, title, summary, sections, period_from, period_to,
                                     stages, keywords, not_this_if, template_id, status)
            VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB), ?, ?)
            ON CONFLICT (card_id, version) DO UPDATE SET
                title = EXCLUDED.title, summary = EXCLUDED.summary, sections = EXCLUDED.sections,
                period_from = EXCLUDED.period_from, period_to = EXCLUDED.period_to, stages = EXCLUDED.stages,
                keywords = EXCLUDED.keywords, not_this_if = EXCLUDED.not_this_if,
                template_id = EXCLUDED.template_id, status = EXCLUDED.status
            """;

    private static final String UPSERT_TEMPLATE = """
            INSERT INTO reply_templates (template_id, version, card_id, effective_from, effective_to, status,
                                         blocks, variables, documents, summary_line, procedural_objection_options)
            VALUES (?, ?, ?, CAST(? AS DATE), CAST(? AS DATE), ?, CAST(? AS JSONB), CAST(? AS JSONB),
                    CAST(? AS JSONB), ?, CAST(? AS JSONB))
            ON CONFLICT (template_id, version) DO UPDATE SET
                card_id = EXCLUDED.card_id, effective_from = EXCLUDED.effective_from,
                effective_to = EXCLUDED.effective_to, status = EXCLUDED.status, blocks = EXCLUDED.blocks,
                variables = EXCLUDED.variables, documents = EXCLUDED.documents,
                summary_line = EXCLUDED.summary_line,
                procedural_objection_options = EXCLUDED.procedural_objection_options
            """;

    private static final String UPSERT_STAGE = """
            INSERT INTO stage_templates (stage, version, effective_from, effective_to, status, sections)
            VALUES (?, ?, CAST(? AS DATE), CAST(? AS DATE), ?, CAST(? AS JSONB))
            ON CONFLICT (stage, version) DO UPDATE SET
                effective_from = EXCLUDED.effective_from, effective_to = EXCLUDED.effective_to,
                status = EXCLUDED.status, sections = EXCLUDED.sections
            """;

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    /** @return rows upserted per table, or {@code null} when skipped (PRIVATE_SEED_DIR unset / missing) */
    public SeedCounts load() {
        String dirSetting = properties.getPrivateSeedDir();
        if (dirSetting == null || dirSetting.isBlank()) {
            log.info("private seed skipped: PRIVATE_SEED_DIR is not set");
            return null;
        }
        File root = new File(dirSetting.trim());
        if (!root.isDirectory()) {
            log.warn("private seed skipped: PRIVATE_SEED_DIR is not a directory");
            return null;
        }

        // Parse and validate everything first so a bad file never leaves a half-loaded catalogue
        List<JsonNode> cards = readAll(new File(root, "cards"), "cards", List.of("card_id", "title", "summary"));
        List<JsonNode> templates = readAll(new File(root, "templates"), "templates", List.of("template_id", "card_id"));
        List<JsonNode> stages = readAll(new File(root, "stage_templates"), "stage_templates", List.of("stage", "sections"));

        for (JsonNode c : cards) {
            jdbcTemplate.update(UPSERT_CARD,
                    text(c, "card_id"), c.path("version").asInt(1), text(c, "title"), text(c, "summary"),
                    json(c, "sections", "[]"), text(c, "period_from"), text(c, "period_to"),
                    json(c, "stages", "[]"), json(c, "keywords", "[]"), json(c, "not_this_if", "[]"),
                    text(c, "template_id"), status(c));
        }
        for (JsonNode t : templates) {
            jdbcTemplate.update(UPSERT_TEMPLATE,
                    text(t, "template_id"), t.path("version").asInt(1), text(t, "card_id"),
                    text(t, "effective_from"), text(t, "effective_to"), status(t),
                    json(t, "blocks", "[]"), json(t, "variables", "[]"), json(t, "documents", "[]"),
                    text(t, "summary_line"), json(t, "procedural_objection_options", "[]"));
        }
        for (JsonNode s : stages) {
            jdbcTemplate.update(UPSERT_STAGE,
                    text(s, "stage"), s.path("version").asInt(1), text(s, "effective_from"),
                    text(s, "effective_to"), status(s), json(s, "sections", "{}"));
        }

        SeedCounts counts = new SeedCounts(cards.size(), templates.size(), stages.size());
        log.info("private seed loaded: {} issue card(s), {} reply template(s), {} stage template(s)",
                counts.cards(), counts.templates(), counts.stageTemplates());
        return counts;
    }

    private List<JsonNode> readAll(File dir, String kind, List<String> requiredKeys) {
        List<JsonNode> rows = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return rows;  // folder absent: nothing of this kind
        }
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            JsonNode node;
            try {
                node = objectMapper.readTree(files[i]);
            } catch (Exception e) {
                // exception messages can quote file content: report only the kind, number and type
                throw new IllegalStateException("private seed: " + kind + " file #" + (i + 1) + " is not valid JSON ("
                        + e.getClass().getSimpleName() + ")");
            }
            List<JsonNode> items = new ArrayList<>();
            if (node.isArray()) node.forEach(items::add);
            else items.add(node);
            for (JsonNode item : items) {
                for (String key : requiredKeys) {
                    if (!item.hasNonNull(key) || (item.get(key).asText().isBlank() && !item.get(key).isContainerNode())) {
                        throw new IllegalStateException("private seed: " + kind + " file #" + (i + 1)
                                + " has an entry without required field '" + key + "'");
                    }
                }
                rows.add(item);
            }
        }
        return rows;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String json(JsonNode n, String key, String dflt) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return dflt;
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("private seed: cannot serialise field '" + key + "'");
        }
    }

    private static String status(JsonNode n) {
        String s = text(n, "status");
        return s == null || s.isBlank() ? "draft" : s;
    }
}
