package com.noticedesk.api.service.matching;

import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-notice shortlist of catalogue cards for the matching prompt (v2, hybrid). Plain code does the stage filter and
 * the label scoring; an optional AI title pass ({@code ShortlistTitleAgent}, run by the caller) adds the cards it
 * judges plausible by meaning:
 * <ol>
 *   <li><b>stage pool</b>: a card is kept when its {@code stages} fit the notice ({@link #NOTICE_STAGES}); a
 *       document with no form name and no known type is placed by the letter patterns ({@link #LETTER_FORMS});
 *       cards with stage {@code other} always pass; a notice that cannot be placed gets no stage filter;</li>
 *   <li><b>label scoring</b>: each LABEL keyword (a section / rule reference, a form name, a notification /
 *       circular number; free-text phrase keywords are ignored) weighs {@code ln(N / df)}, N the catalogue size and
 *       df the number of cards carrying it ({@link LabelIndex}); a pool card scores the sum of the weights of its
 *       distinct keywords found in the notice. Kept: every card scoring at least {@code relativeCutoff} x the best
 *       score; when that keeps fewer than {@code minCards}, the top {@code minCards} by score (positive scores
 *       only). When the AI title pass named at least one card, the stricter {@code relativeCutoffWithAi} /
 *       {@code minCardsWithAi} apply instead (the AI reads by meaning what labels catch by section);</li>
 *   <li><b>union</b>: full cards = label picks + AI picks + the NOT THIS IF siblings of the AI picks that are inside
 *       the stage pool. Any other sibling a full card names ("use NDK-xxx") is listed by title only
 *       ({@link Shortlist#titlesOnly()}), so the matcher can still name it as an alternate;</li>
 *   <li><b>floor</b>: fewer than {@link #MIN_CARDS} full cards: the stage pool (level {@code stage}), or the full
 *       catalogue when the pool is the full catalogue (level {@code full});</li>
 *   <li><b>absence cards</b>: a show cause notice or intimation ({@link #ABSENCE_KEYS}, or a DRC-01 / DRC-01A /
 *       ASMT-10 heading) whose text cites no section and no rule at all also gets, in full, every catalogue card whose
 *       title contains one of {@code absenceTitlePatterns} (case-insensitive; "no section", "vague"): the defect of
 *       such a notice is the missing provision, so no label can point at its card;</li>
 *   <li><b>defect-check cards</b>: the reply-stage and appeal-stage cards of every code defect check that applies to
 *       the document ({@link DefectChecker#applicableCardIds}: limitation_73, premature_74, din, signature,
 *       drc01a_reply_time) are always added in full, whatever their label score or stage, so a defect the code finds
 *       after matching always had its card in front of the matcher.</li>
 * </ol>
 * The output keeps catalogue order, so the same inputs always give the same shortlist.
 */
public final class CardShortlister {

    /** Label scoring and AI title pass both ran. */
    public static final String LEVEL_HYBRID = "hybrid";
    /** Label scoring only (AI title pass off, skipped or failed). */
    public static final String LEVEL_LABELS = "labels";
    public static final String LEVEL_STAGE = "stage";
    public static final String LEVEL_FULL = "full";

    /** AI title pass: picks used. */
    public static final String AI_USED = "used";
    /** AI title pass: switched off, or not available (no agent, stub provider). */
    public static final String AI_OFF = "off";
    /** AI title pass: called but failed, refused or was refused by the budget (labels only; warn flag). */
    public static final String AI_SKIPPED = "skipped";

    /** Below this many full cards the stage pool is used. */
    public static final int MIN_CARDS = 3;

    /** Defaults of {@code matching.shortlist.relative-cutoff} / {@code matching.shortlist.min-cards}. */
    public static final double DEFAULT_RELATIVE_CUTOFF = 0.35;
    public static final int DEFAULT_MIN_CARDS = 15;
    /** Defaults of {@code matching.shortlist.relative-cutoff-with-ai} / {@code min-cards-with-ai}. */
    public static final double DEFAULT_RELATIVE_CUTOFF_WITH_AI = 0.5;
    public static final int DEFAULT_MIN_CARDS_WITH_AI = 12;
    /** Default of {@code matching.shortlist.absence-title-patterns}. */
    public static final List<String> DEFAULT_ABSENCE_TITLE_PATTERNS = List.of("no section", "vague");

    /** Notice keys of a show cause notice or intimation, the documents the absence cards are added for. */
    static final Set<String> ABSENCE_KEYS = Set.of("scn_73", "scn_74", "scn_74a", "scn", "generic_scn", "drc_01a", "asmt_10");
    /** DRC-01 / DRC-01A / ASMT-10 in the document heading. */
    private static final Pattern ABSENCE_HEADING = Pattern.compile("(?i)\\b(?:DRC[\\s_\\-]*0?1A?|ASMT[\\s_\\-]*10)\\b");

    /** Card stage that passes every notice type. */
    static final String STAGE_OTHER = "other";

    /**
     * Scoring thresholds (without AI picks, and when the AI title pass named at least one card) and the title patterns
     * of the absence cards (null = the defaults; blank entries are dropped).
     */
    public record Options(double relativeCutoff, int minCards, double relativeCutoffWithAi, int minCardsWithAi,
                          List<String> absenceTitlePatterns) {
        public static final Options DEFAULT = new Options(DEFAULT_RELATIVE_CUTOFF, DEFAULT_MIN_CARDS,
                DEFAULT_RELATIVE_CUTOFF_WITH_AI, DEFAULT_MIN_CARDS_WITH_AI);

        public Options {
            if (!(relativeCutoff >= 0) || relativeCutoff > 1) relativeCutoff = DEFAULT_RELATIVE_CUTOFF;
            if (minCards < 0) minCards = DEFAULT_MIN_CARDS;
            if (!(relativeCutoffWithAi >= 0) || relativeCutoffWithAi > 1) relativeCutoffWithAi = DEFAULT_RELATIVE_CUTOFF_WITH_AI;
            if (minCardsWithAi < 0) minCardsWithAi = DEFAULT_MIN_CARDS_WITH_AI;
            List<String> patterns = new ArrayList<>();
            for (String p : absenceTitlePatterns != null ? absenceTitlePatterns : DEFAULT_ABSENCE_TITLE_PATTERNS) {
                if (p != null && !p.isBlank()) patterns.add(p.trim().toLowerCase(Locale.ROOT));
            }
            absenceTitlePatterns = List.copyOf(patterns);
        }

        /** The default absence title patterns. */
        public Options(double relativeCutoff, int minCards, double relativeCutoffWithAi, int minCardsWithAi) {
            this(relativeCutoff, minCards, relativeCutoffWithAi, minCardsWithAi, null);
        }

        /** The same thresholds with or without AI picks. */
        public Options(double relativeCutoff, int minCards) {
            this(relativeCutoff, minCards, relativeCutoff, minCards);
        }

        /** The thresholds that apply when the AI title pass named at least one card. */
        Options withAi() {
            return new Options(relativeCutoffWithAi, minCardsWithAi, relativeCutoffWithAi, minCardsWithAi, absenceTitlePatterns);
        }
    }

    /**
     * Notice key -> the card stages a notice of that key may be answered with. A notice key is the detected notice
     * type ({@link StageDetector#detect}), a form found in the document heading ({@link #HEADING_FORMS}), a letter
     * pattern ({@link #LETTER_FORMS}) or "appeal" when {@link StageResolver} says the response is an appeal; the
     * allowed stages of every key found are united (conservative: a doubtful notice sees more cards, never fewer). A
     * key not in this table (other, unknown, anti_profiteering, a triage label nobody mapped) switches the stage
     * filter off unless a letter pattern places the document. Edit here only.
     */
    static final Map<String, Set<String>> NOTICE_STAGES = Map.ofEntries(
            // show cause notices
            Map.entry("scn_73", Set.of("scn_73", "generic_scn")),
            Map.entry("scn_74", Set.of("scn_74", "generic_scn")),
            Map.entry("scn_74a", Set.of("scn_73", "scn_74", "generic_scn")),
            Map.entry("scn", Set.of("scn_73", "scn_74", "generic_scn")),
            Map.entry("generic_scn", Set.of("generic_scn")),
            // pre-SCN intimations
            Map.entry("drc_01a", Set.of("drc_01a", "generic_scn")),
            Map.entry("asmt_10", Set.of("asmt_10", "generic_scn")),
            // orders -> appeal (RFD-06 / REG-05 / MOV-09 also see their subject's reply-stage cards)
            Map.entry("appeal", Set.of("order_appeal")),
            Map.entry("order", Set.of("order_appeal")),
            Map.entry("drc_07", Set.of("order_appeal")),
            Map.entry("rfd_06", Set.of("order_appeal", "refund")),
            Map.entry("reg_05", Set.of("order_appeal", "registration")),
            Map.entry("mov_09", Set.of("order_appeal", "inspection")),
            // registration: REG-17 (SCN for cancellation), REG-19 (cancellation order: revocation or appeal)
            Map.entry("registration", Set.of("registration")),
            Map.entry("reg_17", Set.of("registration")),
            Map.entry("reg_19", Set.of("registration", "order_appeal")),
            // refund
            Map.entry("refund", Set.of("refund")),
            Map.entry("rfd_08", Set.of("refund")),
            // recovery / attachment; best-judgement assessment (Section 62 / ASMT-13) is answered with a representation
            // against the recovery that follows it
            Map.entry("recovery", Set.of("recovery")),
            Map.entry("drc_13", Set.of("recovery")),
            Map.entry("drc_22", Set.of("recovery")),
            Map.entry("best_judgement", Set.of("recovery")),
            // inspection / detention (Section 129 / 130)
            Map.entry("inspection", Set.of("inspection")),
            Map.entry("mov_07", Set.of("inspection")),
            // audit
            Map.entry("audit", Set.of("audit")),
            Map.entry("adt_01", Set.of("audit"))
    );

    /** Notice keys that carry no stage information (they neither filter nor switch the filter off). */
    private static final Set<String> NO_INFO_KEYS = Set.of("", "other", "unknown", "needs_review");

    /** Form-name separator: "DRC-07", "DRC 07", "DRC_07", "DRC07". */
    private static final String SEP = "[\\s_\\-]*";

    /** Forms / headings read from the document head, each with its notice key. */
    private static final List<Map.Entry<Pattern, String>> HEADING_FORMS = List.of(
            heading("\\bDRC" + SEP + "0?7\\b", "drc_07"),
            heading("\\bRFD" + SEP + "0?6\\b", "rfd_06"),
            heading("\\bREG" + SEP + "0?5\\b", "reg_05"),
            heading("\\bMOV" + SEP + "0?9\\b", "mov_09"),
            heading("\\border" + SEP + "in" + SEP + "(?:original|appeal)\\b", "order"),
            heading("\\bREG" + SEP + "19\\b", "reg_19"),
            heading("\\bREG" + SEP + "17\\b", "reg_17"),
            heading("\\bDRC" + SEP + "0?1A\\b", "drc_01a"),
            heading("\\bASMT" + SEP + "10\\b", "asmt_10"),
            heading("\\bADT" + SEP + "0?1\\b", "adt_01"),
            heading("\\bRFD" + SEP + "0?8\\b", "rfd_08"),
            heading("\\bMOV" + SEP + "0?7\\b", "mov_07"),
            heading("\\bsection\\s*(?:129|130)\\b", "inspection"),
            heading("\\bDRC" + SEP + "22\\b", "drc_22"),
            heading("\\bDRC" + SEP + "13\\b", "drc_13"),
            heading("\\brecovery\\s+letter\\b", "recovery")
    );

    /**
     * Letter patterns, read only when the notice type and the heading forms give no stage (a letter with no form
     * name): the pattern found EARLIEST anywhere in the text places the document (ties: list order). Mirrors the
     * {@link StageResolver} table for the inputs whose response stage has its own cards.
     */
    static final List<Map.Entry<Pattern, String>> LETTER_FORMS = List.of(
            heading("\\bsection\\s*62\\b|\\bASMT" + SEP + "13\\b", "best_judgement"),
            heading("\\brecovery\\b", "recovery"),
            heading("\\bsection\\s*79\\b", "recovery"),
            heading("\\bsection\\s*(?:129|130)\\b|\\bMOV" + SEP + "0?7\\b", "inspection"),
            heading("\\bsection\\s*65\\b|\\bADT" + SEP + "0?1\\b", "audit"),
            heading("\\bREG" + SEP + "19\\b", "reg_19"),
            heading("\\bREG" + SEP + "17\\b", "reg_17"),
            heading("\\bRFD" + SEP + "0?8\\b", "rfd_08"),
            heading("\\bRFD" + SEP + "0?6\\b", "rfd_06"),
            heading("\\bDRC" + SEP + "13\\b", "drc_13"),
            heading("\\bDRC" + SEP + "22\\b", "drc_22")
    );

    private static Map.Entry<Pattern, String> heading(String regex, String key) {
        return Map.entry(Pattern.compile("(?i)" + regex), key);
    }

    /** The stage pool of a notice: the cards whose stages fit, and the notice keys read (empty: no filter). */
    public record StagePool(List<IssueCard> cards, List<String> noticeKeys) {
        public int size() {
            return cards.size();
        }
    }

    /**
     * Shortlist and how it was built: the full cards (catalogue order), the cards listed by title only, the level,
     * the catalogue and stage-pool sizes, the label picks and AI picks (raw sets: they may overlap), the number of
     * siblings added in full, the AI pass status ({@link #AI_USED} / {@link #AI_OFF} / {@link #AI_SKIPPED}), the
     * notice keys the stage filter read, whether the AI picks came from an answer whose JSON could not be read, the
     * absence cards added and the defect-check cards added (raw sets, see the class comment).
     */
    public record Shortlist(List<IssueCard> cards, List<IssueCard> titlesOnly, String level, int catalogueSize, int poolSize,
                            Set<String> labelPicks, Set<String> aiPicks, int siblingCount, String aiStatus,
                            List<String> noticeKeys, boolean aiParseFallback, Set<String> absencePicks, Set<String> defectPicks) {

        public Shortlist(List<IssueCard> cards, List<IssueCard> titlesOnly, String level, int catalogueSize, int poolSize,
                         Set<String> labelPicks, Set<String> aiPicks, int siblingCount, String aiStatus,
                         List<String> noticeKeys) {
            this(cards, titlesOnly, level, catalogueSize, poolSize, labelPicks, aiPicks, siblingCount, aiStatus, noticeKeys, false,
                    Set.of(), Set.of());
        }

        /** This shortlist, marked as built from AI picks found in an unreadable answer (flag {@code warn:shortlist_ai_parse_fallback}). */
        public Shortlist withAiParseFallback(boolean fallback) {
            return new Shortlist(cards, titlesOnly, level, catalogueSize, poolSize, labelPicks, aiPicks, siblingCount, aiStatus,
                    noticeKeys, fallback, absencePicks, defectPicks);
        }

        public Shortlist {
            cards = cards != null ? List.copyOf(cards) : List.of();
            titlesOnly = titlesOnly != null ? List.copyOf(titlesOnly) : List.of();
            labelPicks = labelPicks != null ? Collections.unmodifiableSet(new LinkedHashSet<>(labelPicks)) : Set.of();
            aiPicks = aiPicks != null ? Collections.unmodifiableSet(new LinkedHashSet<>(aiPicks)) : Set.of();
            absencePicks = absencePicks != null ? Collections.unmodifiableSet(new LinkedHashSet<>(absencePicks)) : Set.of();
            defectPicks = defectPicks != null ? Collections.unmodifiableSet(new LinkedHashSet<>(defectPicks)) : Set.of();
            aiStatus = aiStatus != null ? aiStatus : AI_OFF;
            noticeKeys = noticeKeys != null ? List.copyOf(noticeKeys) : List.of();
        }

        public int size() {
            return cards.size();
        }

        public boolean isFull() {
            return LEVEL_FULL.equals(level);
        }

        /** True when the AI title pass was called and could not be used (flag {@code warn:shortlist_ai_skipped}). */
        public boolean aiSkipped() {
            return AI_SKIPPED.equals(aiStatus);
        }

        /** Full card ids in shortlist order (no repeats). */
        public List<String> cardIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (IssueCard c : cards) ids.add(c.cardId());
            return List.copyOf(ids);
        }

        /** Ids of the cards listed by title only. */
        public List<String> titlesOnlyIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (IssueCard c : titlesOnly) ids.add(c.cardId());
            return List.copyOf(ids);
        }

        /**
         * "27 cards (pool 128; labels 19, ai 14, siblings 6; titles-only 11)" for logs, the runner and the partner note;
         * ", absence 2" follows the siblings when absence cards were added, then ", defects 3" when defect-check cards were.
         */
        public String describe() {
            return describe(details());
        }

        /** The {@link #describe()} line rebuilt from {@link #details()} (the live runner reads the flag details). */
        public static String describe(Map<String, Object> d) {
            Object status = d.get("ai_status");
            String ai = AI_USED.equals(status) ? String.valueOf(d.get("ai")) : String.valueOf(status);
            Object level = d.get("level");
            String prefix = LEVEL_STAGE.equals(level) || LEVEL_FULL.equals(level) ? "level " + level + "; " : "";
            return d.get("size") + " cards (" + prefix + "pool " + d.get("pool") + "; labels " + d.get("labels") + ", ai " + ai
                    + ", siblings " + d.get("siblings") + (d.get("absence") != null ? ", absence " + d.get("absence") : "")
                    + (d.get("defects") != null ? ", defects " + d.get("defects") : "")
                    + "; titles-only " + d.get("titles_only") + ")";
        }

        /** Details for the {@code info:shortlist} draft flag. */
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("size", size());
            d.put("level", level);
            d.put("catalogue_size", catalogueSize);
            d.put("pool", poolSize);
            d.put("labels", labelPicks.size());
            d.put("ai", aiPicks.size());
            d.put("ai_status", aiStatus);
            d.put("siblings", siblingCount);
            d.put("titles_only", titlesOnly.size());
            if (!absencePicks.isEmpty()) d.put("absence", absencePicks.size());
            if (!defectPicks.isEmpty()) d.put("defects", defectPicks.size());
            if (aiParseFallback) d.put("ai_parse_fallback", true);
            return d;
        }
    }

    private CardShortlister() {}

    /** The whole catalogue as a level {@code full} shortlist. */
    public static Shortlist full(List<IssueCard> cards) {
        List<IssueCard> all = cards != null ? List.copyOf(cards) : List.of();
        return new Shortlist(all, List.of(), LEVEL_FULL, all.size(), all.size(), Set.of(), Set.of(), 0, AI_OFF, List.of());
    }

    /** Label shortlist with the default thresholds and no AI pass. */
    public static Shortlist shortlist(List<IssueCard> cards, String noticeType, ResponseStage responseStage, String noticeText) {
        return shortlist(cards, noticeType, responseStage, noticeText, Options.DEFAULT, null, AI_OFF);
    }

    /**
     * The shortlist for one notice.
     *
     * @param cards         the visible catalogue, in catalogue order
     * @param noticeType    the detected notice type ({@link StageDetector#detect})
     * @param responseStage the response the document calls for ({@link StageResolver#resolve}), or null
     * @param noticeText    the full OCR text
     * @param options       scoring thresholds
     * @param aiPicks       card ids the AI title pass returned (null when it did not run or failed)
     * @param aiStatus      {@link #AI_USED} / {@link #AI_OFF} / {@link #AI_SKIPPED}
     */
    public static Shortlist shortlist(List<IssueCard> cards, String noticeType, ResponseStage responseStage, String noticeText,
                                      Options options, Set<String> aiPicks, String aiStatus) {
        List<IssueCard> all = cards != null ? cards : List.of();
        String text = noticeText != null ? noticeText : "";
        Options opt = options != null ? options : Options.DEFAULT;
        Map<String, IssueCard> byId = CatalogueService.byId(all);

        // step 1: stage pool
        StagePool pool = stagePool(all, noticeType, responseStage, text);
        Set<String> poolIds = new HashSet<>();
        for (IssueCard c : pool.cards()) poolIds.add(c.cardId());

        Set<String> ai = new LinkedHashSet<>();
        if (aiPicks != null) {
            for (String id : aiPicks) if (id != null && byId.containsKey(id)) ai.add(id);
        }

        // step 2: label scoring (stricter thresholds when the AI title pass named a card)
        Set<String> labelPicks = labelPicks(pool.cards(), LabelIndex.of(all), Labels.in(text), ai.isEmpty() ? opt : opt.withAi());

        // step 3: union with the AI picks and their in-pool siblings
        Set<String> picked = new HashSet<>(labelPicks);
        picked.addAll(ai);
        Set<String> siblings = siblingIds(cardsOf(ai, byId), byId.keySet());
        siblings.retainAll(poolIds);
        siblings.removeAll(picked);
        Set<String> fullIds = new HashSet<>(picked);
        fullIds.addAll(siblings);

        // step 5 (read first so the floor counts them): absence cards of a section-less show cause notice / intimation
        Set<String> absence = absencePicks(all, pool.noticeKeys(), text, opt.absenceTitlePatterns());
        fullIds.addAll(absence);
        // step 6 (also read first): the cards of every code defect check that applies to the document, always in full
        Set<String> defects = new LinkedHashSet<>();
        for (String id : DefectChecker.applicableCardIds(noticeType, noticeType, responseStage, text)) {
            if (byId.containsKey(id)) defects.add(id);
        }
        fullIds.addAll(defects);

        String level = ai.isEmpty() && !AI_USED.equals(aiStatus) ? LEVEL_LABELS : LEVEL_HYBRID;
        if (fullIds.size() < MIN_CARDS) {
            // step 4: floor
            if (pool.size() >= all.size()) {
                return new Shortlist(all, List.of(), LEVEL_FULL, all.size(), pool.size(), labelPicks, ai, 0, aiStatus,
                        pool.noticeKeys(), false, absence, defects);
            }
            fullIds = new HashSet<>(poolIds);
            fullIds.addAll(absence);
            fullIds.addAll(defects);
            level = LEVEL_STAGE;
        }

        List<IssueCard> chosen = new ArrayList<>();
        for (IssueCard c : all) if (fullIds.contains(c.cardId())) chosen.add(c);
        // titles only: siblings the full cards name that are not shown in full
        Set<String> named = siblingIds(chosen, byId.keySet());
        named.removeAll(fullIds);
        List<IssueCard> titlesOnly = new ArrayList<>();
        for (IssueCard c : byId.values()) if (named.contains(c.cardId())) titlesOnly.add(c);
        int siblingsAdded = LEVEL_STAGE.equals(level) ? 0 : siblings.size();
        return new Shortlist(chosen, titlesOnly, level, all.size(), pool.size(), labelPicks, ai, siblingsAdded, aiStatus,
                pool.noticeKeys(), false, absence, defects);
    }

    /**
     * Step 5: the ids of the catalogue cards whose title contains one of {@code patterns} (lower-case substrings), when
     * the document is a show cause notice or intimation (a notice key in {@link #ABSENCE_KEYS}, or a DRC-01 / DRC-01A /
     * ASMT-10 heading) and its text cites no section and no rule; else empty.
     */
    static Set<String> absencePicks(List<IssueCard> all, List<String> noticeKeys, String text, List<String> patterns) {
        Set<String> out = new LinkedHashSet<>();
        if (patterns == null || patterns.isEmpty()) return out;
        boolean scn = noticeKeys.stream().anyMatch(ABSENCE_KEYS::contains)
                || ABSENCE_HEADING.matcher(text.substring(0, Math.min(StageResolver.HEADING_CHARS, text.length()))).find();
        if (!scn || Labels.in(text).citesSectionOrRule()) return out;
        for (IssueCard c : all) {
            String title = c.title() != null ? c.title().toLowerCase(Locale.ROOT) : "";
            for (String p : patterns) {
                if (title.contains(p)) {
                    out.add(c.cardId());
                    break;
                }
            }
        }
        return out;
    }

    private static List<IssueCard> cardsOf(Collection<String> ids, Map<String, IssueCard> byId) {
        List<IssueCard> out = new ArrayList<>();
        for (String id : ids) {
            IssueCard c = byId.get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ step 1

    /** The stage pool of a notice (step 1 alone). */
    public static StagePool stagePool(List<IssueCard> cards, String noticeType, ResponseStage responseStage, String noticeText) {
        List<IssueCard> all = cards != null ? cards : List.of();
        String text = noticeText != null ? noticeText : "";
        List<String> keys = noticeKeys(noticeType, responseStage, text);
        Set<String> allowed = allowedStages(keys);
        if (allowed == null) {
            String letter = letterKey(text);
            if (letter != null) {
                keys = List.of(letter);
                allowed = allowedStages(keys);
            }
        }
        if (allowed == null) return new StagePool(List.copyOf(all), List.of());
        List<IssueCard> out = new ArrayList<>();
        for (IssueCard c : all) {
            if (stageFits(c, allowed)) out.add(c);
        }
        return new StagePool(List.copyOf(out), List.copyOf(keys));
    }

    /** The notice keys read from the notice type, the response stage and the document heading. */
    static List<String> noticeKeys(String noticeType, ResponseStage responseStage, String text) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(noticeType != null ? noticeType.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_") : "");
        if (responseStage == ResponseStage.APPEAL) keys.add("appeal");
        String head = text.substring(0, Math.min(StageResolver.HEADING_CHARS, text.length()));
        for (Map.Entry<Pattern, String> form : HEADING_FORMS) {
            if (form.getKey().matcher(head).find()) keys.add(form.getValue());
        }
        keys.removeAll(NO_INFO_KEYS);
        return new ArrayList<>(keys);
    }

    /** The key of the letter pattern found earliest in {@code text} (ties: list order), or null. */
    static String letterKey(String text) {
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Map.Entry<Pattern, String> form : LETTER_FORMS) {
            Matcher m = form.getKey().matcher(text);
            if (m.find() && m.start() < bestAt) {
                best = form.getValue();
                bestAt = m.start();
            }
        }
        return best;
    }

    /** The card stages the keys allow, or null (no stage filter) when no key is known or one is not in the table. */
    static Set<String> allowedStages(List<String> keys) {
        if (keys.isEmpty()) return null;
        Set<String> out = new TreeSet<>();
        for (String k : keys) {
            Set<String> stages = NOTICE_STAGES.get(k);
            if (stages == null) return null;
            out.addAll(stages);
        }
        return out;
    }

    /** True when the card may answer a notice whose allowed card stages are {@code allowed} (null = every stage). */
    static boolean stageFits(IssueCard card, Set<String> allowed) {
        if (allowed == null || card.stages() == null || card.stages().isEmpty()) return true;
        for (String s : card.stages()) {
            if (s == null) continue;
            String st = s.trim().toLowerCase(Locale.ROOT);
            if (STAGE_OTHER.equals(st) || allowed.contains(st)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ step 2

    /**
     * The label keywords of a catalogue and their weights: per card its distinct label keywords (by canonical
     * {@link Labels#key()}), and per keyword {@code ln(N / df)}. Built once per catalogue and cached (the last
     * catalogue seen; an equal list reuses it).
     */
    static final class LabelIndex {
        private final List<IssueCard> source;
        private final Map<String, Map<String, Labels>> cardKeywords;
        private final Map<String, Double> weights;

        private static volatile LabelIndex cached;

        private LabelIndex(List<IssueCard> source) {
            this.source = source;
            Map<String, Map<String, Labels>> perCard = new HashMap<>();
            Map<String, Integer> df = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (IssueCard c : source) {
                if (c == null || c.cardId() == null || !seen.add(c.cardId())) continue;   // latest version only
                Map<String, Labels> kws = new LinkedHashMap<>();
                if (c.keywords() != null) {
                    for (String kw : c.keywords()) {
                        Labels l = labelKeyword(kw);
                        if (l != null) kws.putIfAbsent(l.key(), l);
                    }
                }
                perCard.put(c.cardId(), kws);
                for (String k : kws.keySet()) df.merge(k, 1, Integer::sum);
            }
            int n = Math.max(1, seen.size());
            Map<String, Double> w = new HashMap<>();
            for (Map.Entry<String, Integer> e : df.entrySet()) w.put(e.getKey(), Math.log((double) n / e.getValue()));
            this.cardKeywords = perCard;
            this.weights = w;
        }

        static LabelIndex of(List<IssueCard> cards) {
            List<IssueCard> list = cards != null ? cards : List.of();
            LabelIndex c = cached;
            if (c != null && (c.source == list || c.source.equals(list))) return c;
            LabelIndex fresh = new LabelIndex(List.copyOf(list));
            cached = fresh;
            return fresh;
        }

        /** Weight of a keyword key; 0 when unknown. */
        double weight(String key) {
            return weights.getOrDefault(key, 0.0);
        }

        /** Score of a card: the sum of the weights of its distinct label keywords found among {@code found}. */
        double score(String cardId, Labels found) {
            double s = 0;
            for (Map.Entry<String, Labels> e : cardKeywords.getOrDefault(cardId, Map.of()).entrySet()) {
                if (found.covers(e.getValue())) s += weight(e.getKey());
            }
            return s;
        }
    }

    /** Label score of every card of {@code cards} against {@code noticeText} (catalogue order; for tests and the dry run). */
    public static Map<String, Double> scores(List<IssueCard> catalogue, List<IssueCard> cards, String noticeText) {
        LabelIndex index = LabelIndex.of(catalogue);
        Labels found = Labels.in(noticeText);
        Map<String, Double> out = new LinkedHashMap<>();
        for (IssueCard c : cards) out.putIfAbsent(c.cardId(), index.score(c.cardId(), found));
        return out;
    }

    /** Step 2: the pool cards kept by score (see the class comment), in pool order. */
    static Set<String> labelPicks(List<IssueCard> pool, LabelIndex index, Labels found, Options opt) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double best = 0;
        for (IssueCard c : pool) {
            if (scores.containsKey(c.cardId())) continue;
            double s = index.score(c.cardId(), found);
            scores.put(c.cardId(), s);
            best = Math.max(best, s);
        }
        Set<String> out = new LinkedHashSet<>();
        if (best <= 0) return out;
        double cut = opt.relativeCutoff() * best;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() > 0 && e.getValue() >= cut - 1e-9) out.add(e.getKey());
        }
        if (out.size() < opt.minCards()) {
            List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
            ranked.removeIf(e -> e.getValue() <= 0);
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));   // stable: pool order on ties
            Set<String> top = new HashSet<>();
            for (int i = 0; i < Math.min(opt.minCards(), ranked.size()); i++) top.add(ranked.get(i).getKey());
            top.addAll(out);
            out = new LinkedHashSet<>();
            for (String id : scores.keySet()) if (top.contains(id)) out.add(id);
        }
        return out;
    }

    /** Words that may stand next to a label in a keyword without making it a free-text phrase. */
    private static final Pattern LABEL_FILLER = Pattern.compile(
            "(?i)\\b(?:of|the|cgst|sgst|igst|utgst|gst|act|rules?|2017|read|with|r/w|and|under|u/s|no|form)\\b|[\\s,.;:()/&\\-]+");

    /**
     * The labels of a keyword when the keyword IS a label (a section / rule reference, a form name, a notification /
     * circular number, possibly with "of the CGST Act" around it), else null (a free-text phrase keyword).
     */
    static Labels labelKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        Labels labels = new Labels();
        String rest = labels.collect(keyword, true);
        if (labels.isEmpty()) return null;
        return LABEL_FILLER.matcher(rest).replaceAll("").isEmpty() ? labels : null;
    }

    /**
     * Labels found in a text. A section / rule is its number plus its sub-section path ("16" + [2, c]); a form, a
     * notification or a circular is one canonical string ("drc1a", "ewaybill", "notification 13/2017",
     * "circular 183").
     */
    static final class Labels {
        private final List<Ref> sections = new ArrayList<>();
        private final List<Ref> rules = new ArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();

        record Ref(String number, List<String> path) {
            /** True when this keyword reference hits the text reference {@code t}: same number, path a prefix of t's. */
            boolean hits(Ref t) {
                return number.equals(t.number) && t.path.size() >= path.size() && t.path.subList(0, path.size()).equals(path);
            }
        }

        /** Canonical text of these labels ("s16(2)(c)+r36(4)+drc1a"): equal for keywords that name the same labels. */
        String key() {
            TreeSet<String> parts = new TreeSet<>();
            for (Ref r : sections) parts.add("s" + r.number() + pathText(r.path()));
            for (Ref r : rules) parts.add("r" + r.number() + pathText(r.path()));
            parts.addAll(names);
            return String.join("+", parts);
        }

        private static String pathText(List<String> path) {
            StringBuilder sb = new StringBuilder();
            for (String p : path) sb.append('(').append(p).append(')');
            return sb.toString();
        }

        boolean isEmpty() {
            return sections.isEmpty() && rules.isEmpty() && names.isEmpty();
        }

        /** True when at least one section or rule reference was found. */
        boolean citesSectionOrRule() {
            return !sections.isEmpty() || !rules.isEmpty();
        }

        /** True when any of the keyword's labels {@code kw} occurs among these (text) labels. */
        boolean covers(Labels kw) {
            for (Ref k : kw.sections) for (Ref t : sections) if (k.hits(t)) return true;
            for (Ref k : kw.rules) for (Ref t : rules) if (k.hits(t)) return true;
            for (String n : kw.names) if (names.contains(n)) return true;
            return false;
        }

        static Labels in(String text) {
            Labels l = new Labels();
            l.collect(text != null ? text : "", false);
            return l;
        }

        // "Section 16(2)(c)", "Sec. 16 (2)(c)", "u/s 16(4)", "s. 74A", "Sections 16(2)(c), 73 and 74"
        private static final String REF = "(\\d{1,3}(?!\\d)[A-Za-z]{0,2}(?![A-Za-z]))((?:\\s*\\(\\s*[0-9A-Za-z]{1,4}\\s*\\))*)";
        private static final String LIST_SEP = "\\s*(?:,|&|/|\\band\\b|\\bor\\b|\\bread\\s+with\\b|\\br/w\\b)\\s*";
        private static final Pattern SECTION = Pattern.compile(
                "(?i)(?<![A-Za-z])(?:sections?|secs?\\.?|ss?\\.|u/s\\.?)\\s*" + REF + "((?:" + LIST_SEP + "(?!rule)" + REF + ")*)");
        private static final Pattern RULE = Pattern.compile(
                "(?i)(?<![A-Za-z])(?:rules?|r\\.)\\s*" + REF + "((?:" + LIST_SEP + "(?!section)" + REF + ")*)");
        private static final Pattern ONE_REF = Pattern.compile("(?i)" + REF);
        private static final Pattern SUB = Pattern.compile("\\(\\s*([0-9A-Za-z]{1,4})\\s*\\)");
        // form names: ASMT-10, DRC-01A, DRC 01A, GSTR-2A, GSTR 3B, RFD-06, REG-17, MOV-07, ADT-01, APL-01, ITC-04 ...
        private static final Pattern FORM = Pattern.compile(
                "(?i)(?<![A-Za-z0-9])(ASMT|DRC|RFD|REG|MOV|ADT|GSTR|APL|CMP|ITC|PMT|INS|TRAN|ENR|IFF)[\\s_\\-]*(\\d{1,2}[A-Za-z]?)(?![A-Za-z0-9])");
        private static final Pattern EWAY = Pattern.compile("(?i)\\be[\\s\\-]?way[\\s\\-]?bills?\\b|\\bEWB\\b");
        private static final Pattern NOTIFICATION = Pattern.compile(
                "(?i)\\bnotification\\s*(?:no\\.?\\s*)?(\\d{1,3})\\s*/\\s*(\\d{4})");
        private static final Pattern CIRCULAR = Pattern.compile("(?i)\\bcircular\\s*(?:no\\.?\\s*)?(\\d{1,4})\\b");

        /** Adds every label of {@code s}; returns {@code s} with the label text removed (for the keyword test). */
        String collect(String s, boolean keyword) {
            String rest = s;
            rest = refs(rest, SECTION, sections);
            rest = refs(rest, RULE, rules);
            rest = names(rest, NOTIFICATION, m -> "notification " + Integer.parseInt(m.group(1)) + "/" + m.group(2));
            rest = names(rest, CIRCULAR, m -> "circular " + Integer.parseInt(m.group(1)));
            rest = names(rest, EWAY, m -> "ewaybill");
            rest = names(rest, FORM, m -> m.group(1).toLowerCase(Locale.ROOT) + stripZeros(m.group(2).toLowerCase(Locale.ROOT)));
            return rest;
        }

        private static String refs(String s, Pattern p, List<Ref> into) {
            Matcher m = p.matcher(s);
            StringBuilder rest = new StringBuilder();
            while (m.find()) {
                into.add(ref(m.group(1), m.group(2)));
                Matcher more = ONE_REF.matcher(m.group(3) != null ? m.group(3) : "");
                while (more.find()) into.add(ref(more.group(1), more.group(2)));
                m.appendReplacement(rest, " ");
            }
            m.appendTail(rest);
            return rest.toString();
        }

        private static Ref ref(String number, String subs) {
            List<String> path = new ArrayList<>();
            Matcher sm = SUB.matcher(subs != null ? subs : "");
            while (sm.find()) path.add(sm.group(1).toLowerCase(Locale.ROOT));
            return new Ref(stripZeros(number.toLowerCase(Locale.ROOT)), List.copyOf(path));
        }

        private String names(String s, Pattern p, java.util.function.Function<Matcher, String> canon) {
            Matcher m = p.matcher(s);
            StringBuilder rest = new StringBuilder();
            while (m.find()) {
                names.add(canon.apply(m));
                m.appendReplacement(rest, " ");
            }
            m.appendTail(rest);
            return rest.toString();
        }

        private static String stripZeros(String s) {
            String t = s.replaceFirst("^0+(?=\\d)", "");
            return t.isEmpty() ? s : t;
        }
    }

    // ------------------------------------------------------------------ step 3 (siblings)

    private static final Pattern CARD_ID_TOKEN = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]+(?:-[A-Za-z]+)*-\\d+[A-Za-z]?(?![A-Za-z0-9])");

    /** Ids of catalogue cards named in the NOT THIS IF rules of {@code cards}. */
    static Set<String> siblingIds(List<IssueCard> cards, Set<String> catalogueIds) {
        Set<String> out = new LinkedHashSet<>();
        for (IssueCard c : cards) {
            if (c.notThisIf() == null) continue;
            for (String rule : c.notThisIf()) {
                if (rule == null) continue;
                Matcher m = CARD_ID_TOKEN.matcher(rule);
                while (m.find()) {
                    if (catalogueIds.contains(m.group()) && !m.group().equals(c.cardId())) out.add(m.group());
                }
            }
        }
        return out;
    }
}
