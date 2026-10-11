package com.noticedesk.api.service.matching;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Counts the numbered paragraphs (1., 2., ... or 1), 2), ...) of a notice from its text. */
public final class NoticeParagraphs {

    private static final Pattern NUMBERED_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:para(?:graph)?\\.?\\s*)?(\\d{1,3})[.)][ \\t]+\\S", Pattern.CASE_INSENSITIVE);
    private static final int MAX_PARAS = 300;

    private NoticeParagraphs() {}

    /**
     * Length of the 1, 2, 3, ... sequence of numbered paragraph starts, in document order (numbers out
     * of sequence, such as list items inside a paragraph, are skipped). 0 when the text has none.
     */
    public static int count(String noticeText) {
        if (noticeText == null || noticeText.isBlank()) return 0;
        Matcher m = NUMBERED_LINE.matcher(noticeText);
        int expected = 1;
        while (m.find() && expected <= MAX_PARAS) {
            if (Integer.parseInt(m.group(1)) == expected) expected++;
        }
        return expected - 1;
    }
}
