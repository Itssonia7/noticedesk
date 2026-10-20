package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.ShortlistTitleAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.service.matching.CardShortlister;
import com.noticedesk.api.service.matching.CardShortlister.Shortlist;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.StageDetector;
import com.noticedesk.api.service.matching.StageResolver;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.PrivateSetSupport;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shortlist dry run on a private set, zero API cost by default. Skipped unless {@code SHORTLIST_DRY_RUN_DIR} is set.
 *
 * <pre>
 * $SHORTLIST_DRY_RUN_DIR/cards/*.json    private-seed card JSON (card_id, title, keywords, not_this_if, stages, ...)
 * $SHORTLIST_DRY_RUN_DIR/notices/*.json  notice_id, document_type, full_ocr_text, answer_key.issues[].card_ids
 * </pre>
 * For every notice the label shortlist is built (no model call) and one line prints the pool size, the label picks,
 * the full and titles-only counts and, per answer-key card, whether it is shown in full, listed by title only, or
 * missed; a summary gives the median / p90 / max shortlist size and the recall. Options:
 * <ul>
 *   <li>{@code SHORTLIST_DRY_RUN_NOTICES=<a.json,b.json>}: only these notice files (names or notice ids);</li>
 *   <li>{@code SHORTLIST_DRY_RUN_AI=true}: also calls the {@link ShortlistTitleAgent} per notice (PAID, small) and
 *       reports the union's sizes and recall plus the AI cost. Needs {@code ANTHROPIC_API_KEY} and
 *       {@code LLM_MODEL_SHORTLIST} or {@code LLM_MODEL_MATCHING}; runs under {@link LiveRunGuard} (commit check,
 *       {@code LIVE_MAX_RUN_COST_INR} / {@code LIVE_MAX_NOTICE_COST_INR} caps, fail fast, {@code LIVE_SMOKE});</li>
 *   <li>{@code MATCHING_SHORTLIST_RELATIVE_CUTOFF} / {@code MATCHING_SHORTLIST_MIN_CARDS}: thresholds (as in the app).</li>
 * </ul>
 * Prints notice ids, card ids and counts only, never notice text or card content.
 */
class ShortlistDryRunTest {

    private static String env(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v.trim() : Optional.ofNullable(System.getProperty(name)).map(String::trim).orElse(null);
    }

    /** One notice of the private set. */
    record Notice(String file, String id, String noticeType, String text, List<String> expected) {}

    /** Where an answer-key card ended up. */
    static String placement(Shortlist s, String cardId) {
        if (s.cardIds().contains(cardId)) return "full";
        if (s.titlesOnlyIds().contains(cardId)) return "titles-only";
        return "MISSED";
    }

    /** Nearest-rank percentile of {@code values} (0 when empty). */
    static int percentile(List<Integer> values, double p) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    /** Totals of one shortlist variant (labels only, or the union with the AI picks). */
    static final class Tally {
        final List<Integer> sizes = new ArrayList<>();
        int expected;
        int full;
        int titlesOnly;

        void add(Shortlist s, List<String> expectedIds) {
            sizes.add(s.size());
            for (String id : expectedIds) {
                expected++;
                String p = placement(s, id);
                if ("full".equals(p)) full++;
                else if ("titles-only".equals(p)) titlesOnly++;
            }
        }

        String line(String name) {
            return String.format(Locale.ROOT, "%s: %d notices | size median %d, p90 %d, max %d | recall full %s, full or titles-only %s (%d expected card(s))",
                    name, sizes.size(), percentile(sizes, 0.5), percentile(sizes, 0.9), sizes.isEmpty() ? 0 : Collections.max(sizes),
                    pct(full, expected), pct(full + titlesOnly, expected), expected);
        }

        private static String pct(int n, int d) {
            return d == 0 ? "N/A" : String.format(Locale.ROOT, "%.1f%% (%d)", 100.0 * n / d, n);
        }
    }

    static List<Notice> readNotices(File dir, ObjectMapper om, Set<String> only) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        List<Notice> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            JsonNode root = om.readTree(f);
            String id = root.path("notice_id").asText(f.getName());
            if (only != null && !only.contains(f.getName()) && !only.contains(id)) continue;
            String docType = root.path("document_type").asText("");
            String section = root.hasNonNull("section") ? root.get("section").asText() : null;
            List<String> expected = new ArrayList<>();
            for (JsonNode issue : root.path("answer_key").path("issues")) {
                for (JsonNode c : issue.path("card_ids")) {
                    String cid = c.asText("").trim();
                    if (!cid.isEmpty() && !expected.contains(cid)) expected.add(cid);
                }
            }
            out.add(new Notice(f.getName(), id, StageDetector.detect(section, docType), root.path("full_ocr_text").asText(""), expected));
        }
        return out;
    }

    @Test
    void shortlistDryRun() throws Exception {
        String dirSetting = env("SHORTLIST_DRY_RUN_DIR");
        Assumptions.assumeTrue(dirSetting != null, "SHORTLIST_DRY_RUN_DIR not set");
        File root = new File(dirSetting);
        ObjectMapper om = new ObjectMapper();
        AppProperties props = TestAppProperties.fromApplicationYml();
        props.getMatching().setAllowDraftCards(true);   // as the live runners

        List<IssueCard> read = PrivateSetSupport.readCards(new File(root, "cards"), om);
        List<IssueCard> cards = CatalogueService.visibleCards(read, true, props.getCatalogue().isIncludeProvisional());
        Set<String> only = null;
        String onlySetting = env("SHORTLIST_DRY_RUN_NOTICES");
        if (onlySetting != null) {
            only = new LinkedHashSet<>();
            for (String s : onlySetting.split(",")) if (!s.isBlank()) only.add(s.trim());
        }
        List<Notice> notices = readNotices(new File(root, "notices"), om, only);
        if (cards.isEmpty() || notices.isEmpty()) fail("dry run: " + cards.size() + " visible card(s), " + notices.size() + " notice(s) found");
        AppProperties.Matching.Shortlist sp = props.getMatching().getShortlist();
        CardShortlister.Options options = new CardShortlister.Options(sp.getRelativeCutoff(), sp.getMinCards());
        System.out.printf(Locale.ROOT, "Shortlist dry run: %d card(s) read, %d visible; %d notice(s); relative cutoff %.2f, min cards %d%n",
                read.size(), cards.size(), notices.size(), options.relativeCutoff(), options.minCards());

        // ---- optional paid AI title pass, under the live-run safeguards
        boolean ai = "true".equalsIgnoreCase(env("SHORTLIST_DRY_RUN_AI"));
        ShortlistTitleAgent agent = null;
        LiveRunGuard guard = null;
        RunCostLedger ledger = null;
        ApiUsageLogService usage = null;
        if (ai) {
            guard = LiveRunGuard.fromEnvironment();
            LiveRunGuard.CommitCheck commit = guard.checkCommit(LiveRunGuard.gitHead());
            System.out.println(commit.message());
            if (!commit.allowed()) fail(commit.message());
            String apiKey = env("ANTHROPIC_API_KEY");
            if (apiKey == null) fail("REFUSED: SHORTLIST_DRY_RUN_AI=true needs ANTHROPIC_API_KEY");
            props.getLlm().setProviderPrimary("anthropic");
            props.getLlm().getAnthropic().setApiKey(apiKey);
            String ws = env("ANTHROPIC_WORKSPACE_ID");
            if (ws != null) props.getLlm().getAnthropic().setWorkspaceId(ws);
            props.getLlm().setModelMatching(guard.model("LLM_MODEL_MATCHING", props.getLlm().getModelMatching()));
            props.getLlm().setModelShortlist(guard.model("LLM_MODEL_SHORTLIST", props.getLlm().getModelShortlist()));
            String model = props.getLlm().shortlistModel();
            if (model == null || model.isBlank()) fail("REFUSED: set LLM_MODEL_SHORTLIST or LLM_MODEL_MATCHING (no model names are built into the runner)");
            usage = new ApiUsageLogService(null, props);
            if (!usage.hasPrice(model)) fail("REFUSED: no price configured for model " + model + " under noticedesk.pricing.models in application.yml");
            ledger = RunCostLedger.attach(usage, System.out);
            guard.attach(usage);
            guard.printSettings(System.out);
            agent = new ShortlistTitleAgent(props, om, usage, new LlmFactory(props));
            System.out.printf("AI title pass: model=%s effort=%s max_tokens=%d%n", model,
                    props.getLlm().effortForAgent(AppProperties.Llm.SHORTLIST), props.getLlm().maxTokensForAgent(AppProperties.Llm.SHORTLIST, false));
            if (guard.smoke()) notices = notices.subList(0, 1);
        }

        Tally labels = new Tally();
        Tally union = new Tally();
        int aiFailed = 0;
        BigDecimal spent = BigDecimal.ZERO;
        String stopped = null;
        for (Notice n : notices) {
            StageResolver.ResponseStage stage = StageResolver.resolve(n.noticeType(), n.text());
            Shortlist byLabels = CardShortlister.shortlist(cards, n.noticeType(), stage, n.text(), options, null, CardShortlister.AI_OFF);
            labels.add(byLabels, n.expected());
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%s [%s] pool %d | labels %d -> %d full, %d titles-only (%s)",
                    n.id(), n.noticeType(), byLabels.poolSize(), byLabels.labelPicks().size(), byLabels.size(),
                    byLabels.titlesOnly().size(), byLabels.level()));
            Shortlist withAi = null;
            if (agent != null) {
                BigDecimal cap = guard.noticeCapFor(spent);
                if (cap.signum() <= 0) {
                    stopped = "run cap reached";
                    break;
                }
                int checkpoint = ledger.size();
                ShortlistTitleAgent.Picks picks;
                try {
                    picks = agent.pickCards(cards, n.noticeType(), n.text(), null, new NoticeCallBudget(Integer.MAX_VALUE, cap, usage));
                } catch (LlmFatalException e) {
                    stopped = e.getMessage();
                    break;
                } finally {
                    spent = spent.add(ledger.totalInrSince(checkpoint));
                }
                if (picks == null) aiFailed++;
                withAi = CardShortlister.shortlist(cards, n.noticeType(), stage, n.text(), options,
                        picks != null ? picks.cardIds() : null, picks != null ? CardShortlister.AI_USED : CardShortlister.AI_SKIPPED);
                union.add(withAi, n.expected());
                line.append(String.format(Locale.ROOT, " | union %s", withAi.describe()));
            }
            List<String> placed = new ArrayList<>();
            for (String id : n.expected()) {
                placed.add(id + " " + placement(byLabels, id) + (withAi != null ? " / " + placement(withAi, id) : ""));
            }
            line.append(" | expected: ").append(placed.isEmpty() ? "none" : String.join(", ", placed));
            System.out.println(line);
        }

        System.out.println("---- summary");
        System.out.println(labels.line("Labels only"));
        if (agent != null) {
            System.out.println(union.line("Labels + AI"));
            System.out.printf(Locale.ROOT, "AI title pass: %d call(s) unusable; cost %.4f INR (run cap INR %s)%n", aiFailed,
                    spent.doubleValue(), guard.maxRunCostInr().toPlainString());
        }
        if (stopped != null) System.out.println("RUN STOPPED: " + stopped);
    }

    // ------------------------------------------------------------------ offline checks of the tool (synthetic data)

    @Test
    void percentilesAreNearestRank() {
        assertEquals(0, percentile(List.of(), 0.5));
        assertEquals(3, percentile(List.of(5, 1, 3), 0.5));
        assertEquals(9, percentile(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0.9));
        assertEquals(10, percentile(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 1.0));
    }

    @Test
    void dryRunOnASyntheticSetPrintsPlacementsAndTheSummary(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path cards = java.nio.file.Files.createDirectories(tmp.resolve("cards"));
        java.nio.file.Path notices = java.nio.file.Files.createDirectories(tmp.resolve("notices"));
        java.nio.file.Files.writeString(cards.resolve("cards.json"), """
                [{"card_id": "T-001", "title": "ITC not in GSTR-2B", "summary": "s", "keywords": ["Section 16(2)(aa)"], "stages": ["scn_73"],
                  "not_this_if": ["use T-004"], "status": "active"},
                 {"card_id": "T-002", "title": "Interest", "summary": "s", "keywords": ["Section 50"], "stages": ["scn_73"], "status": "active"},
                 {"card_id": "T-003", "title": "Defence 16(5)", "summary": "s", "keywords": ["Section 16(5)"], "stages": ["scn_73"], "status": "active"},
                 {"card_id": "T-004", "title": "Appeal", "summary": "s", "keywords": ["Section 107"], "stages": ["order_appeal"], "status": "active"},
                 {"card_id": "T-005", "title": "Late fee", "summary": "s", "keywords": ["Section 47"], "stages": ["scn_73"], "status": "active"}]
                """);
        java.nio.file.Files.writeString(notices.resolve("n1.json"), """
                {"notice_id": "N-1", "document_type": "scn_73",
                 "full_ocr_text": "Show cause notice under Section 73. ITC denied under Section 16(2)(aa); interest under Section 50; late fee under Section 47.",
                 "answer_key": {"issues": [{"card_ids": ["T-001"]}, {"card_ids": ["T-003"]}, {"card_ids": ["T-004"]}]}}
                """);
        java.nio.file.Files.writeString(notices.resolve("n2.json"), """
                {"notice_id": "N-2", "document_type": "scn_73", "full_ocr_text": "Section 50 interest", "answer_key": {"issues": []}}
                """);
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setProperty("SHORTLIST_DRY_RUN_DIR", tmp.toString());
        System.setProperty("SHORTLIST_DRY_RUN_NOTICES", "n1.json");
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            shortlistDryRun();
        } finally {
            System.setOut(original);
            System.clearProperty("SHORTLIST_DRY_RUN_DIR");
            System.clearProperty("SHORTLIST_DRY_RUN_NOTICES");
        }
        String out = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(out.contains("Shortlist dry run: 5 card(s) read, 5 visible; 1 notice(s); relative cutoff 0.35, min cards 15"), out);
        assertTrue(out.contains("N-1 [scn_73] pool 4 | labels 3 -> 3 full, 1 titles-only (labels)"), out);
        assertTrue(out.contains("expected: T-001 full, T-003 MISSED, T-004 titles-only"), out);
        assertTrue(out.contains("Labels only: 1 notices | size median 3, p90 3, max 3 | recall full 33.3% (1), full or titles-only 66.7% (2) (3 expected card(s))"), out);
        assertFalse(out.contains("N-2"), "SHORTLIST_DRY_RUN_NOTICES limits the notices");
        assertFalse(out.contains("Show cause notice"), "no notice text is printed");
        assertFalse(out.contains("Labels + AI"), "no AI pass without SHORTLIST_DRY_RUN_AI");
    }
}
