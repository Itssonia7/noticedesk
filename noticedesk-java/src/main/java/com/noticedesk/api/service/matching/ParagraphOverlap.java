package com.noticedesk.api.service.matching;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Finds AI paragraphs that restate a template block the writer reused (live run, notice 004: para 4.1
 * was template block BLK-011-04A and para 4.3 the AI paraphrase of the same point). Similarity is the
 * Jaccard index of the two paragraphs' normalised word sets (lower case, HTML and DEV draft tags removed,
 * common function words ignored).
 */
public final class ParagraphOverlap {

    /** AI paragraphs at or above this similarity to a reused template block are dropped. */
    public static final double RESTATEMENT_THRESHOLD = 0.6;

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DEV_TAG = Pattern.compile("(?i)\\[DEV - not CA reviewed\\]|DRAFT - partner to review\\.?");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "of", "and", "or", "to", "in", "on", "by", "for", "with", "as", "at", "is", "are",
            "was", "were", "be", "been", "it", "its", "this", "that", "which", "from", "any", "has", "have", "had",
            "not", "no", "under", "such", "said", "also", "submitted", "respectfully", "humbly", "further");

    /** Result of {@link #dropRestatements}: the remaining HTML and how many paragraphs were dropped. */
    public record Filtered(String html, int dropped) {}

    private ParagraphOverlap() {}

    static Set<String> tokens(String html) {
        if (html == null) return Set.of();
        String text = DEV_TAG.matcher(TAG.matcher(html).replaceAll(" ")).replaceAll(" ").toLowerCase(Locale.ROOT);
        Set<String> out = new HashSet<>();
        for (String t : NON_WORD.split(text)) {
            if (!t.isEmpty() && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    /** Jaccard index of the normalised word sets of two HTML / text fragments (0 when either is empty). */
    public static double jaccard(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    /**
     * Removes each top-level &lt;p&gt; of {@code html} whose similarity to any of {@code reusedBlocks}
     * is at least {@code threshold}. Text outside &lt;p&gt; elements (lists, tables) is kept as is.
     */
    public static Filtered dropRestatements(String html, Collection<String> reusedBlocks, double threshold) {
        if (html == null || html.isBlank() || reusedBlocks == null || reusedBlocks.isEmpty()) return new Filtered(html, 0);
        StringBuilder out = new StringBuilder();
        int dropped = 0;
        for (String part : html.split("(?=<p>)")) {
            if (part.startsWith("<p>") && restates(part, reusedBlocks, threshold)) {
                dropped++;
                int end = part.indexOf("</p>");
                if (end >= 0) out.append(part.substring(end + 4));   // keep anything after the paragraph
                continue;
            }
            out.append(part);
        }
        return new Filtered(out.toString(), dropped);
    }

    private static boolean restates(String paragraph, Collection<String> reusedBlocks, double threshold) {
        int end = paragraph.indexOf("</p>");
        String p = end >= 0 ? paragraph.substring(0, end + 4) : paragraph;
        for (String block : reusedBlocks) {
            if (jaccard(p, block) >= threshold) return true;
        }
        return false;
    }
}
