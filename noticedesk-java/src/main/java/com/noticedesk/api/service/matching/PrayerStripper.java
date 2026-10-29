package com.noticedesk.api.service.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes a prayer the AI writers added at the end of their section 04/06 text. The reply's prayer is
 * Section 12 (stage template) only, so trailing paragraphs that pray for relief, ask for the proceedings
 * to be dropped or for a personal hearing, or are headed "Prayer", are dropped. Text before them is kept.
 */
public final class PrayerStripper {

    /** Result: the HTML without the trailing prayer, and how many paragraphs were removed. */
    public record Stripped(String html, int removed) {}

    private static final Pattern BLOCK = Pattern.compile(
            "<(p|li|h[1-6])\\b[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRAYER = Pattern.compile(
            "(?i)\\bpray(?:s|ed|er|ing)?\\b"
            + "|\\bwherefore\\b"
            + "|\\brelief(?:s)?\\s+(?:sought|claimed|prayed)"
            + "|\\b(?:requested|request|urged)\\s+(?:that\\s+)?(?:the\\s+)?(?:entire\\s+|impugned\\s+|present\\s+)?"
            + "(?:proceedings?|show\\s+cause\\s+notice|notice|demand)[^.]{0,120}?\\b(?:dropped|quashed|set\\s+aside|withdrawn|closed)\\b"
            + "|\\bpersonal\\s+hearing\\b[^.]{0,80}?\\b(?:granted|afforded|provided|given)\\b");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private PrayerStripper() {}

    private static int count(CharSequence s, String token) {
        int n = 0;
        String lower = s.toString().toLowerCase();
        for (int i = lower.indexOf(token); i >= 0; i = lower.indexOf(token, i + 1)) n++;
        return n;
    }

    public static Stripped stripTrailingPrayer(String html) {
        if (html == null || html.isBlank()) return new Stripped(html, 0);
        List<int[]> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(html);
        while (m.find()) blocks.add(new int[]{m.start(), m.end()});
        if (blocks.isEmpty()) return new Stripped(html, 0);

        // the prayer must be the tail: nothing but whitespace / closing list tags after the last block
        String after = html.substring(blocks.get(blocks.size() - 1)[1]);
        if (!after.replaceAll("(?i)</?(ul|ol)>", "").isBlank()) return new Stripped(html, 0);

        int cut = blocks.size();
        while (cut > 0) {
            int[] b = blocks.get(cut - 1);
            String text = TAGS.matcher(html.substring(b[0], b[1])).replaceAll(" ");
            if (!PRAYER.matcher(text).find()) break;
            cut--;
        }
        if (cut == blocks.size()) return new Stripped(html, 0);
        String kept = html.substring(0, cut == 0 ? blocks.get(0)[0] : blocks.get(cut - 1)[1]);
        kept = kept.replaceAll("(?i)<(ul|ol)>\\s*$", "");
        StringBuilder out = new StringBuilder(kept.strip());
        for (String list : new String[]{"ul", "ol"}) {   // close lists the removed items left open
            int open = count(out, "<" + list + ">") - count(out, "</" + list + ">");
            for (int i = 0; i < open; i++) out.append("</").append(list).append('>');
        }
        return new Stripped(out.toString(), blocks.size() - cut);
    }
}
