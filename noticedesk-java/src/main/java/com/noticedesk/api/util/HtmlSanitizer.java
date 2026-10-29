package com.noticedesk.api.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allow-list sanitiser for AI-written HTML fragments (v7 Stage 3).
 *
 * <p>Only {@code p, ul, ol, li, b, i, strong, em, table, tr, td, th} survive, always without
 * attributes. Script/style blocks and HTML comments (which the assembler uses for paragraph
 * numbering markers) are removed entirely; every other tag is dropped but its text kept.
 */
public class HtmlSanitizer {

    public static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "ul", "ol", "li", "b", "i", "strong", "em", "table", "tr", "td", "th"
    );

    private static final Pattern TAG_PATTERN = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>");

    private HtmlSanitizer() {}

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        String clean = html.replaceAll("(?is)<script[^>]*>.*?</script\\s*>", "")
                           .replaceAll("(?is)<style[^>]*>.*?</style\\s*>", "")
                           .replaceAll("(?s)<!--.*?-->", "")
                           .replaceAll("(?s)<!--.*", "");

        Matcher matcher = TAG_PATTERN.matcher(clean);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String tagName = matcher.group(1).toLowerCase();
            if (ALLOWED_TAGS.contains(tagName)) {
                boolean isClosing = matcher.group(0).startsWith("</");
                matcher.appendReplacement(sb, isClosing ? "</" + tagName + ">" : "<" + tagName + ">");
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);

        // Any '<' left over is not part of an allowed tag (e.g. "<img" without '>'); neutralise it.
        return sb.toString().replaceAll("<(?!/?(p|ul|ol|li|b|i|strong|em|table|tr|td|th)>)", "&lt;").trim();
    }
}
