package com.noticedesk.api.service.matching;

import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-notice shortlist of catalogue cards for the matching prompt, chosen by plain code from labels (no model call,
 * no ranking, no fixed size):
 * <ol>
 *   <li><b>stage</b>: a card is kept when its {@code stages} fit the notice ({@link #NOTICE_STAGES});</li>
 *   <li><b>keyword</b>: of those, a card is kept when one of its LABEL keywords (a section / rule reference, a form
 *       name, a notification / circular number) occurs in the notice text; free-text phrase keywords are ignored;</li>
 *   <li><b>siblings</b>: every card a kept card's NOT THIS IF rules name ("use NDK-xxx") is added, one level only;</li>
 *   <li><b>floor</b>: fewer than {@link #MIN_CARDS} cards: the keyword filter is dropped (level {@code stage}); still
 *       fewer: the full catalogue (level {@code full}).</li>
 * </ol>
 * The output keeps catalogue order, so the same inputs always give the same shortlist.
 */
public final class CardShortlister {

    public static final String LEVEL_KEYWORD = "keyword";
    public static final String LEVEL_STAGE = "stage";
    public static final String LEVEL_FULL = "full";

    /** Below this many cards the next wider level is used. */
    public static final int MIN_CARDS = 3;

    /** Card stage that passes every notice type. */
    static final String STAGE_OTHER = "other";

    /**
     * Notice key -> the card stages a notice of that key may be answered with. A notice key is the detected notice
     * type ({@link StageDetector#detect}), a form found in the document heading ({@link #HEADING_FORMS}) or "appeal"
     * when {@link StageResolver} says the response is an appeal; the allowed stages of every key found are united
     * (conservative: a doubtful notice sees more cards, never fewer). A key not in this table (other, unknown,
     * anti_profiteering, a triage label nobody mapped) switches the stage filter off. Edit here only.
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
            // recovery / attachment
            Map.entry("recovery", Set.of("recovery")),
            Map.entry("drc_13", Set.of("recovery")),
            Map.entry("drc_22", Set.of("recovery")),
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

    private static Map.Entry<Pattern, String> heading(String regex, String key) {
        return Map.entry(Pattern.compile("(?i)" + regex), key);
    }

    /**
     * Shortlist and how it was built: the cards (catalogue order), the level used, the catalogue size and the count
     * after each step ({@code stageCount} after the stage filter, {@code keywordCount} after the keyword filter,
     * {@code siblingCount} cards added as siblings), and the notice keys the stage filter read (empty: no filter).
     */
    public record Shortlist(List<IssueCard> cards, String level, int catalogueSize, int stageCount, int keywordCount,
                            int siblingCount, List<String> noticeKeys) {

        public int size() {
            return cards.size();
        }

        public boolean isFull() {
            return LEVEL_FULL.equals(level);
        }

        /** Card ids in shortlist order (no repeats). */
        public List<String> cardIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (IssueCard c : cards) ids.add(c.cardId());
            return List.copyOf(ids);
        }

        /** "23 cards (level keyword; stage 61, keyword 19, siblings 4)" for logs, the runner and the partner note. */
        public String describe() {
            return size() + " cards (level " + level + "; stage " + stageCount + ", keyword " + keywordCount
                    + ", siblings " + siblingCount + ")";
        }

        /** Details for the {@code info:shortlist} draft flag. */
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("size", size());
            d.put("level", level);
            d.put("catalogue_size", catalogueSize);
            d.put("stage", stageCount);
            d.put("keyword", keywordCount);
            d.put("siblings", siblingCount);
            return d;
        }
    }

    private CardShortlister() {}

    /** The whole catalogue as a level {@code full} shortlist (the widening call). */
    public static Shortlist full(List<IssueCard> cards) {
        List<IssueCard> all = cards != null ? List.copyOf(cards) : List.of();
        return new Shortlist(all, LEVEL_FULL, all.size(), all.size(), all.size(), 0, List.of());
    }

    /**
     * The shortlist for one notice.
     *
     * @param cards         the visible catalogue, in catalogue order
     * @param noticeType    the detected notice type ({@link StageDetector#detect})
     * @param responseStage the response the document calls for ({@link StageResolver#resolve}), or null
     * @param noticeText    the full OCR text
     */
    public static Shortlist shortlist(List<IssueCard> cards, String noticeType, ResponseStage responseStage, String noticeText) {
        List<IssueCard> all = cards != null ? cards : List.of();
        String text = noticeText != null ? noticeText : "";

        // step 1: stage filter
        List<String> keys = noticeKeys(noticeType, responseStage, text);
        Set<String> allowed = allowedStages(keys);
        List<IssueCard> byStage = new ArrayList<>();
        for (IssueCard c : all) {
            if (stageFits(c, allowed)) byStage.add(c);
        }

        // step 2: keyword filter on the label keywords
        Labels found = Labels.in(text);
        List<IssueCard> byKeyword = new ArrayList<>();
        for (IssueCard c : byStage) {
            if (keywordHit(c, found)) byKeyword.add(c);
        }

        // step 3: siblings named in the kept cards' NOT THIS IF rules (one level)
        Set<String> kept = new HashSet<>();
        for (IssueCard c : byKeyword) kept.add(c.cardId());
        Set<String> siblingIds = siblingIds(byKeyword, CatalogueService.byId(all).keySet());
        siblingIds.removeAll(kept);
        List<IssueCard> withSiblings = new ArrayList<>();
        for (IssueCard c : all) {
            if (kept.contains(c.cardId()) || siblingIds.contains(c.cardId())) withSiblings.add(c);
        }
        int siblingsAdded = (int) all.stream().filter(c -> siblingIds.contains(c.cardId())).count();

        // step 4: floor and widening
        List<IssueCard> chosen;
        String level;
        if (withSiblings.size() >= MIN_CARDS) {
            chosen = withSiblings;
            level = LEVEL_KEYWORD;
        } else if (byStage.size() >= MIN_CARDS && byStage.size() < all.size()) {
            chosen = byStage;
            level = LEVEL_STAGE;
        } else {
            chosen = all;
            level = LEVEL_FULL;
        }
        return new Shortlist(List.copyOf(chosen), level, all.size(), byStage.size(), byKeyword.size(), siblingsAdded,
                List.copyOf(keys));
    }

    // ------------------------------------------------------------------ step 1

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

    static boolean keywordHit(IssueCard card, Labels found) {
        if (card.keywords() == null) return false;
        for (String kw : card.keywords()) {
            Labels labels = labelKeyword(kw);
            if (labels != null && found.covers(labels)) return true;
        }
        return false;
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

        boolean isEmpty() {
            return sections.isEmpty() && rules.isEmpty() && names.isEmpty();
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

    // ------------------------------------------------------------------ step 3

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
