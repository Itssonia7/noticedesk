package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain issue titles for the reply's summary table and issue headings. The live run at c82df8b on the synthetic
 * Section 73 notice printed "The records of M/s ... ("the noticee") for FY 2021-22" (the notice's narrative intro),
 * "Objection 6: Short payment of tax ... September 2021. The GSTR-3B" (cut mid-sentence) and two issues both titled
 * "Objection 5: Late filing of returns". Rules:
 * <ol>
 *   <li>the notice's own heading of the issue's paragraph, its "Objection N:" / "Issue No. N:" / "Para N" / "Ground N"
 *       prefix removed, cut at the first full stop that ends a sentence (not "No.", "Rs.", "u/s." ...), capped at about
 *       {@value #MAX_CHARS} characters at a word boundary;</li>
 *   <li>a narrative paragraph (no heading, no amount, no allegation) is never a title;</li>
 *   <li>else the matched card title, else the matcher's label, else a code-found defect's title, else "Demand under"
 *       the legal section;</li>
 *   <li>titles are unique ({@link #unique}): issues from the same paragraph get a qualifier from their legal section /
 *       kind ("Late filing of returns - penalty under Section 125");</li>
 *   <li>the client's name never appears in a title.</li>
 * </ol>
 */
public final class IssueTitles {

    private IssueTitles() {}

    static final int MAX_CHARS = 90;

    private static final Pattern HEADING_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:(?:audit\\s+)?(?:objection|issue|ground|para(?:graph)?|point|observation|allegation)\\s*"
            + "(?:no\\.?|number|#)?\\s*[\\dIVXivx]+(?:\\.\\d+)?\\s*[:.\\-–)]\\s*)+");
    private static final Pattern RUPEE = Pattern.compile("(?i)(?:\\bRs\\b\\.?|\\bINR\\b|₹)\\s*\\d");
    private static final Pattern ALLEGATION = Pattern.compile(
            "(?i)\\b(?:contravention|inadmissible|ineligible|wrongly|short\\s+(?:paid|payment)|not\\s+paid|non-payment|excess"
            + "|payable|recoverable|liable|demand(?:ed)?\\b|disallow|irregular|suppress|under-?valu|blocked)");
    /** Words that end with a full stop without ending the sentence. */
    private static final Set<String> ABBREVIATIONS = Set.of("no", "nos", "rs", "u/s", "sl", "viz", "i.e", "e.g", "etc", "govt",
            "ltd", "pvt", "co", "sec", "secs", "dt", "dtd", "m/s", "vs", "v", "st", "art", "cl", "s", "r", "w.e.f", "f", "ref",
            "para", "paras", "mr", "ms", "dr", "inc", "corp", "notfn", "noti", "ld", "hon'ble", "hon");
    private static final Map<String, String> DEFECT_TITLES = Map.of(
            "din", "No valid DIN on the notice",
            "limitation_73", "Notice issued after the limitation period",
            "premature_74", "Section 74 invoked before its conditions are met",
            "signature", "Notice not signed by the proper officer",
            "drc01a_reply_time", "No reasonable time to reply to the DRC-01A");
    private static final List<String> LABEL_KEYS = List.of("label", "issue_label", "title", "ground_label");

    /**
     * The title of one issue (null when nothing usable: the layout prints "Issue N").
     *
     * @param cardTitle  the matched card's title (null when unknown)
     * @param clientName the client's name, never allowed in a title (null when unknown)
     */
    public static String title(MatchedIssue issue, String noticeText, String cardTitle, String clientName) {
        Map<String, Object> f = issue.facts() != null ? issue.facts() : Map.of();
        if (noticeText != null && issue.noticeParas() != null) {
            for (String ref : issue.noticeParas()) {
                String t = headingOf(paragraphText(noticeText, ref));
                if (usable(t, clientName)) return t;
            }
        }
        if (usable(cap(cardTitle), clientName)) return cap(cardTitle).trim();
        for (String k : LABEL_KEYS) {
            Object v = f.get(k);
            String t = v != null ? cap(HEADING_PREFIX.matcher(v.toString()).replaceFirst("")) : null;
            if (usable(t, clientName)) return t;
        }
        Object check = f.get(CodeDefectMerger.FACT_DEFECT_CHECK);
        if (check != null && DEFECT_TITLES.containsKey(check.toString())) return DEFECT_TITLES.get(check.toString());
        Object legal = f.get("legal_section");
        if (legal != null && !legal.toString().isBlank()) {
            return (issue.isDefect() ? "Objection under " : "Demand under ") + legal.toString().trim();
        }
        return null;
    }

    private static boolean usable(String t, String clientName) {
        if (t == null || t.isBlank()) return false;
        if (clientName != null && !clientName.isBlank()) {
            String bare = clientName.replaceFirst("(?i)^\\s*M/s\\.?\\s*", "").trim().toLowerCase(Locale.ROOT);
            if (!bare.isEmpty() && t.toLowerCase(Locale.ROOT).contains(bare)) return false;
            // the name's first word that is not a legal form ("Yamuna") also counts
            String first = bare.split("\\s+")[0];
            if (first.length() >= 4 && Pattern.compile("(?i)\\b" + Pattern.quote(first) + "\\b").matcher(t).find()) return false;
        }
        return !t.toLowerCase(Locale.ROOT).startsWith("the noticee");
    }

    /**
     * The heading of a notice paragraph, or null when it has none: the paragraph's first sentence, its heading prefix
     * removed, when the paragraph had such a prefix, or else when that sentence is short (at most 14 words) and the
     * paragraph carries an allegation or an amount. A narrative paragraph ("The records of ... were audited ...") gives
     * null.
     */
    static String headingOf(String para) {
        if (para == null || para.isBlank()) return null;
        Matcher pre = HEADING_PREFIX.matcher(para);
        boolean prefixed = pre.find() && pre.end() > 0;
        String rest = prefixed ? para.substring(pre.end()) : para;
        String sentence = firstSentence(rest);
        if (sentence.isBlank()) return null;
        if (!prefixed) {
            boolean allegation = RUPEE.matcher(para).find() || ALLEGATION.matcher(para).find();
            if (!allegation || sentence.split("\\s+").length > 14) return null;
        }
        return cap(sentence);
    }

    /** Text up to the first full stop that ends a sentence; abbreviations, decimals and initials do not end it. */
    static String firstSentence(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '.' && c != '?' && c != '!') continue;
            boolean atEnd = i == t.length() - 1;
            if (!atEnd && t.charAt(i + 1) != ' ') continue;   // "3.1", "u/s.73", "i.e"
            if (c == '.') {
                int w = i - 1;
                while (w >= 0 && t.charAt(w) != ' ' && t.charAt(w) != '(') w--;
                String word = t.substring(w + 1, i).toLowerCase(Locale.ROOT);
                if (ABBREVIATIONS.contains(word) || word.matches("[a-z]")) continue;
                // "Rs. 4,44,520" style: an abbreviation followed by a number continues the sentence
                if (!atEnd && i + 2 < t.length() && Character.isDigit(t.charAt(i + 2)) && word.matches("[a-z/]{1,4}")) continue;
                // the next sentence starts with a capital, a digit or a bracket; anything else continues this one
                if (!atEnd && !(Character.isUpperCase(t.charAt(i + 2)) || Character.isDigit(t.charAt(i + 2)) || t.charAt(i + 2) == '(')) continue;
            }
            return t.substring(0, i).replaceAll("[:;,\\s]+$", "").trim();
        }
        return t.replaceAll("[.:;,\\s]+$", "").trim();
    }

    /** At most about {@value #MAX_CHARS} characters, cut at a word boundary, no ellipsis; trailing joiners removed. */
    static String cap(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim().replaceAll("[.:;,]+$", "");
        if (t.length() <= MAX_CHARS) return t;
        int cut = t.lastIndexOf(' ', MAX_CHARS);
        if (cut < 20) cut = MAX_CHARS;
        t = t.substring(0, cut).replaceAll("[\\s,;:(\\-–]+$", "");
        // never end on a joining word ("of", "and", "under" ...)
        return t.replaceAll("(?i)\\s+(?:of|and|or|to|in|on|at|by|for|from|with|under|the|a|an|read|whose|which|that|who|where|when|as)$", "").trim();
    }

    /** The text of notice paragraph {@code ref} ("6" or "2.1"): from its numbered line to the next numbered line. */
    static String paragraphText(String noticeText, String ref) {
        if (ref == null) return null;
        Matcher n = Pattern.compile("^\\s*(\\d{1,3}(?:\\.\\d{1,3})?)").matcher(ref);
        if (!n.find()) return null;
        Matcher start = Pattern.compile("(?m)^\\s*" + Pattern.quote(n.group(1)) + "\\.?\\s+").matcher(noticeText);
        if (!start.find()) return null;
        String tail = noticeText.substring(start.end());
        Matcher next = Pattern.compile("(?m)^\\s*(?:\\d{1,3}(?:\\.\\d{1,3})?\\.?\\s+\\S|\\([a-z]{1,4}\\)\\s|$)").matcher(tail);
        int end = tail.length();
        while (next.find()) {
            if (next.start() > 0) {
                end = next.start();
                break;
            }
        }
        return tail.substring(0, end).replaceAll("\\s+", " ").trim();
    }

    /**
     * The titles made unique: when two or more issues share a title, each gets " - " and a qualifier from its legal
     * section and kind ("late fee and interest (Sections 47 and 50)", "penalty under Section 125"); when that still
     * collides, " (part N)". Input and output are keyed by issue number, in order.
     */
    public static Map<Integer, String> unique(Map<Integer, String> titles, Map<Integer, MatchedIssue> issues) {
        Map<String, List<Integer>> byTitle = new LinkedHashMap<>();
        titles.forEach((no, t) -> { if (t != null) byTitle.computeIfAbsent(t.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(no); });
        Map<Integer, String> out = new LinkedHashMap<>(titles);
        for (List<Integer> group : byTitle.values()) {
            if (group.size() < 2) continue;
            for (int no : group) {
                MatchedIssue i = issues.get(no);
                String q = i != null ? qualifier(i) : null;
                if (q != null) out.put(no, titles.get(no) + " - " + q);
            }
        }
        // still the same (no qualifier, or the same one): number the parts
        Map<String, List<Integer>> again = new LinkedHashMap<>();
        out.forEach((no, t) -> { if (t != null) again.computeIfAbsent(t.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(no); });
        for (List<Integer> group : again.values()) {
            if (group.size() < 2) continue;
            int part = 1;
            Collections.sort(group);
            for (int no : group) out.put(no, out.get(no) + " (part " + part++ + ")");
        }
        return out;
    }

    private static final Pattern SECTION_REF = Pattern.compile("(?<![\\w(/.])(\\d{1,3}(?!\\d)[A-Z]?(?:\\s*\\(\\s*\\d+[A-Za-z]?\\s*\\))*)");

    /** "late fee and interest (Sections 47 and 50)", "penalty under Section 125", "tax under Section 73"; null when unknown. */
    static String qualifier(MatchedIssue i) {
        Object legal = i.facts() != null ? i.facts().get("legal_section") : null;
        List<String> sections = new ArrayList<>();
        if (legal != null) {
            String t = legal.toString().replaceAll("(?i)\\b(?:rules?|notification|circular|form)\\s+[\\w()/.-]+", " ")
                    .replaceAll("(?i)\\bof\\s+the\\s+\\w+\\s+Act.*$", " ");
            Matcher m = SECTION_REF.matcher(t);
            while (m.find()) {
                String s = m.group(1).replaceAll("\\s+", "");
                if (!sections.contains(s)) sections.add(s);
            }
        }
        EnumSet<DemandTotals.AmountKind> kinds = DemandTotals.sectionKinds(i);
        List<String> words = new ArrayList<>();
        for (DemandTotals.AmountKind k : List.of(DemandTotals.AmountKind.TAX, DemandTotals.AmountKind.LATE_FEE,
                DemandTotals.AmountKind.INTEREST, DemandTotals.AmountKind.PENALTY)) {
            if (kinds.contains(k)) words.add(k == DemandTotals.AmountKind.TAX ? "tax" : k.label().toLowerCase(Locale.ROOT));
        }
        if (words.isEmpty()) {
            if (i.isDefect()) words.add("procedural objection");
            else if (i.isConsequential()) words.add("consequential demand");
        }
        if (words.isEmpty() && sections.isEmpty()) return null;
        String what = String.join(" and ", words);
        if (sections.isEmpty()) return what;
        String secs = sections.size() == 1 ? "Section " + sections.get(0)
                : "Sections " + String.join(", ", sections.subList(0, sections.size() - 1)) + " and " + sections.get(sections.size() - 1);
        if (what.isEmpty()) return "under " + secs;
        return words.size() == 1 ? what + " under " + secs : what + " (" + secs + ")";
    }
}
