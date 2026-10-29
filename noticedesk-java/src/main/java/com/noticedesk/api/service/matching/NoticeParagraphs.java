package com.noticedesk.api.service.matching;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Counts the numbered paragraphs (1., 2., ... or 1), 2), ...) of a notice from its text, and finds the statutory ones. */
public final class NoticeParagraphs {

    private static final Pattern NUMBERED_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:para(?:graph)?\\.?\\s*)?(\\d{1,3})[.)][ \\t]+\\S", Pattern.CASE_INSENSITIVE);
    private static final int MAX_PARAS = 300;

    /** A heading that introduces statutory text ("Contravention of the Law", "Provisions", "Statutory provisions"). */
    private static final String STATUTORY_HEADING =
            "(?:contravention\\s+of\\s+(?:the\\s+)?law|(?:relevant\\s+|statutory\\s+|legal\\s+)?provisions?(?:\\s+of\\s+(?:the\\s+)?law)?)";
    /** A heading line on its own (optionally numbered or lettered), e.g. "Contravention of the Law:". */
    private static final Pattern HEADING_LINE = Pattern.compile(
            "(?i)^\\s*(?:\\(?[A-Za-z0-9]{1,3}[.)]\\s*)?" + STATUTORY_HEADING + "\\s*[:.\\-–]*\\s*$");
    /** A paragraph that opens with the heading ("Contravention of the Law: ..."). */
    private static final Pattern HEADING_START = Pattern.compile("(?i)^\\s*" + STATUTORY_HEADING + "\\s*(?:[:.\\-–]|$)");
    /**
     * A paragraph that opens with statutory text: "(A) Section ...", "Section 54 ... provides", "Rule 89 ... provides",
     * or a quoted sub-section such as "(1) Every registered person shall".
     */
    private static final Pattern STATUTORY_START = Pattern.compile(
            "(?is)^\\s*(?:\\(\\s*[A-Z]\\s*\\)\\s*section\\b"
                    + "|(?:\\(\\s*[A-Za-z]\\s*\\)\\s*)?(?:section|rule)\\s+\\d+[A-Z]{0,2}\\b[^.]{0,160}?\\b(?:provides|stipulates)\\b"
                    + "|(?:\\(\\s*[A-Za-z]\\s*\\)\\s*)?[\"“']?\\s*\\(\\d{1,2}[A-Za-z]?\\)\\s+(?:every|any|no|where|the|a|an)\\b[^.]{0,160}?\\bshall\\b)");

    private NoticeParagraphs() {}

    /**
     * Length of the 1, 2, 3, ... sequence of numbered paragraph starts, in document order (numbers out
     * of sequence, such as list items inside a paragraph, are skipped). 0 when the text has none.
     */
    public static int count(String noticeText) {
        return starts(noticeText).size();
    }

    /**
     * Numbers of the paragraphs that are statutory text: the paragraph opens with a statutory heading ("Contravention
     * of the Law", "Provisions", "Statutory provisions") or such a heading stands alone on the line before it, or the
     * paragraph opens with "(A) Section", "Section ... provides", "Rule ... provides" or quotes a sub-section such as
     * "(1) Every registered person shall".
     */
    public static Set<Integer> statutoryParagraphs(String noticeText) {
        Set<Integer> out = new TreeSet<>();
        if (noticeText == null || noticeText.isBlank()) return out;
        for (Map.Entry<Integer, int[]> e : starts(noticeText).entrySet()) {
            int lineStart = e.getValue()[0];
            int bodyFrom = e.getValue()[1];
            String body = noticeText.substring(bodyFrom, Math.min(noticeText.length(), bodyFrom + 400));
            if (HEADING_START.matcher(body).find() || STATUTORY_START.matcher(body).find()
                    || HEADING_LINE.matcher(previousLine(noticeText, lineStart)).matches()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The last non-blank line before {@code lineStart}; "" when none. */
    private static String previousLine(String text, int lineStart) {
        int end = lineStart;
        while (end > 0) {
            int from = text.lastIndexOf('\n', end - 2) + 1;
            String line = text.substring(from, Math.max(from, end - 1));
            if (!line.isBlank()) return line;
            if (from == 0) break;
            end = from;
        }
        return "";
    }

    /** Paragraph number -> {line start, body start (after the number)} of the 1, 2, 3, ... sequence. */
    private static Map<Integer, int[]> starts(String noticeText) {
        Map<Integer, int[]> out = new LinkedHashMap<>();
        if (noticeText == null || noticeText.isBlank()) return out;
        Matcher m = NUMBERED_LINE.matcher(noticeText);
        int expected = 1;
        while (m.find() && expected <= MAX_PARAS) {
            if (Integer.parseInt(m.group(1)) == expected) {
                out.put(expected, new int[]{m.start(), m.end() - 1});
                expected++;
            }
        }
        return out;
    }
}
