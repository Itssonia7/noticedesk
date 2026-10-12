package com.noticedesk.api.service.matching;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-only clean-up of assembled draft text: document lists merged across issues, [[MISSING: x]]
 * markers de-duplicated by a normalised key (shown in their original wording), and the high-stakes executive summary kept to a word budget.
 */
public final class DraftTextCleanup {

    private DraftTextCleanup() {}

    // ------------------------------------------------------------------ documents (Sections 10 / 11 / 14)

    /** Words that do not change which document is meant ("Copies of all ..."). */
    private static final Set<String> DOC_FILLER = Set.of(
            "a", "an", "the", "of", "for", "and", "to", "in", "on", "with", "all", "copy", "copies",
            "relevant", "along", "duly", "self", "attested", "certified", "together");

    /**
     * Removes duplicate documents, keeping the first position of each. Two entries are the same document
     * when they are equal after normalising case, whitespace and punctuation. An entry whose significant
     * words (at least two; every number / period word included) all appear in an earlier entry is dropped;
     * when a later entry covers an earlier one, the later (wider) wording replaces it in the earlier position.
     * "GSTR-3B returns for April 2020 to March 2021" and "Copies of all GSTR-3B and GSTR-1 returns for
     * April 2020 to March 2021" become the second entry, once.
     */
    public static List<String> dedupeDocuments(List<String> documents) {
        List<String> texts = new ArrayList<>();
        List<Set<String>> words = new ArrayList<>();
        Set<String> exact = new HashSet<>();
        if (documents == null) return texts;
        for (String raw : documents) {
            if (raw == null || raw.isBlank()) continue;
            String text = raw.trim().replaceAll("\\s+", " ");
            String norm = normaliseDoc(text);
            if (norm.isEmpty() || !exact.add(norm)) continue;
            Set<String> w = significantWords(norm);
            boolean merged = false;
            if (w.size() >= 2) {
                for (int i = 0; i < texts.size(); i++) {
                    Set<String> prev = words.get(i);
                    if (prev.size() < 2) continue;
                    if (prev.containsAll(w)) {            // already covered by an earlier entry
                        merged = true;
                        break;
                    }
                    if (w.containsAll(prev)) {            // wider wording of an earlier entry: keep its place
                        texts.set(i, text);
                        words.set(i, w);
                        merged = true;
                        break;
                    }
                }
            }
            if (!merged) {
                texts.add(text);
                words.add(w);
            }
        }
        // a widened entry may now cover a later one
        List<String> out = new ArrayList<>();
        List<Set<String>> outWords = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            Set<String> w = words.get(i);
            boolean covered = false;
            if (w.size() >= 2) {
                for (Set<String> prev : outWords) {
                    if (prev.size() >= 2 && prev.containsAll(w)) {
                        covered = true;
                        break;
                    }
                }
            }
            if (!covered) {
                out.add(texts.get(i));
                outWords.add(w);
            }
        }
        return out;
    }

    /** Lower case, punctuation removed (codes joined: "GSTR-3B" -> "gstr3b", "show-cause" -> "show cause"), single spaces. */
    static String normaliseDoc(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("(?<=\\d)[-/.](?=\\w)|(?<=\\w)[-/.](?=\\d)", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static Set<String> significantWords(String normalised) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : normalised.split(" ")) {
            if (t.isEmpty() || DOC_FILLER.contains(t)) continue;
            // plural / singular are the same document ("returns" / "return"); numbers and codes stay as they are
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && t.chars().allMatch(Character::isLetter)) {
                t = t.substring(0, t.length() - 1);
            }
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ [[MISSING: x]] markers

    private static final Pattern DATE_OF_SERVICE = Pattern.compile(
            "^date_(?:and_mode_)?of_service(?:_of_(?:the_)?notice)?$|^service_date$|^date_of_notice_service$");

    /**
     * The canonical key of a [[MISSING: x]] marker: lower case, spaces / hyphens to underscores, and the
     * synonyms of the date of service ("date of service of notice", "date and mode of service") mapped to
     * {@code date_of_service}. {@code date_of_receipt} stays a separate key.
     */
    public static String normaliseMarkerKey(String key) {
        if (key == null) return "";
        String k = key.trim()
                .replaceAll("^\\[\\[\\s*MISSING:\\s*|\\s*\\]\\]$", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (DATE_OF_SERVICE.matcher(k).matches()) return "date_of_service";
        return k;
    }

    /**
     * Markers for display: one per data item, de-duplicated by {@link #normaliseMarkerKey} (synonyms merged),
     * each shown as the original human-readable text of its first occurrence ("[[MISSING: ...]]" wrapper
     * removed, whitespace collapsed). The normalised key is used only to find duplicates.
     */
    public static List<String> dedupeMarkers(Collection<String> markers) {
        Map<String, String> firstText = new LinkedHashMap<>();
        if (markers != null) {
            for (String m : markers) {
                String key = normaliseMarkerKey(m);
                if (key.isEmpty() || firstText.containsKey(key)) continue;
                String text = m.trim().replaceAll("^\\[\\[\\s*MISSING:\\s*|\\s*\\]\\]$", "").replaceAll("\\s+", " ").trim();
                firstText.put(key, text);
            }
        }
        return new ArrayList<>(firstText.values());
    }

    /** Normalised, de-duplicated marker keys in first-seen order. */
    public static List<String> dedupeMarkerKeys(Collection<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys != null) {
            for (String k : keys) {
                String n = normaliseMarkerKey(k);
                if (!n.isEmpty()) out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ word budget (Section 01)

    /** {@code kept} fits the word budget; {@code overflow} is the rest (empty when everything fits). */
    public record WordSplit(String kept, String overflow) {}

    private static final Pattern TOP_LEVEL_BLOCK = Pattern.compile(
            "(?is)<(p|ul|ol|table|h3|blockquote)\\b[^>]*>.*?</\\1>");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.;!?])\\s+(?=[A-Z(\\[])");

    /**
     * Splits HTML at a block boundary so the kept part has at most {@code maxWords} words. When the
     * first block alone is longer, it is cut at the last sentence end that fits (or kept whole when even
     * its first sentence is longer). Nothing is dropped: the remainder is returned as {@code overflow}.
     */
    public static WordSplit limitWords(String html, int maxWords) {
        if (html == null || html.isBlank() || wordCount(html) <= maxWords) {
            return new WordSplit(html == null ? "" : html, "");
        }
        List<String> blocks = new ArrayList<>();
        Matcher m = TOP_LEVEL_BLOCK.matcher(html);
        int last = 0;
        while (m.find()) {
            String between = html.substring(last, m.start());
            if (!between.isBlank()) blocks.add("<p>" + between.trim() + "</p>");
            blocks.add(m.group());
            last = m.end();
        }
        if (last < html.length() && !html.substring(last).isBlank()) blocks.add("<p>" + html.substring(last).trim() + "</p>");

        StringBuilder kept = new StringBuilder();
        StringBuilder overflow = new StringBuilder();
        int used = 0;
        for (String b : blocks) {
            int w = wordCount(b);
            if (overflow.length() == 0 && used + w <= maxWords) {
                kept.append(b);
                used += w;
            } else if (overflow.length() == 0 && used == 0 && b.regionMatches(true, 0, "<p", 0, 2)) {
                String[] parts = splitParagraph(b, maxWords);
                kept.append(parts[0]);
                used = wordCount(parts[0]);
                overflow.append(parts[1]);
            } else {
                overflow.append(b);
            }
        }
        return new WordSplit(kept.toString(), overflow.toString());
    }

    /** One paragraph cut at the last sentence end within the budget: [kept, rest]. */
    private static String[] splitParagraph(String p, int maxWords) {
        Matcher open = Pattern.compile("(?is)^<p\\b[^>]*>").matcher(p);
        String inner = open.find() ? p.substring(open.end()).replaceFirst("(?is)</p>\\s*$", "") : p;
        String[] sentences = SENTENCE_END.split(inner);
        StringBuilder head = new StringBuilder();
        int used = 0;
        int i = 0;
        for (; i < sentences.length; i++) {
            int w = wordCount(sentences[i]);
            if (i > 0 && used + w > maxWords) break;
            if (head.length() > 0) head.append(' ');
            head.append(sentences[i]);
            used += w;
        }
        if (i >= sentences.length) return new String[]{p, ""};
        String tail = String.join(" ", Arrays.copyOfRange(sentences, i, sentences.length));
        return new String[]{"<p>" + head + "</p>", "<p>" + tail + "</p>"};
    }

    public static int wordCount(String html) {
        if (html == null) return 0;
        String text = html.replaceAll("<!--.*?-->", " ").replaceAll("<[^>]+>", " ").trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }
}
