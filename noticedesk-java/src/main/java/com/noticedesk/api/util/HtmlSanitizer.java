package com.noticedesk.api.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HtmlSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "ul", "ol", "li", "b", "i", "strong", "em", "table", "tr", "td", "th", "h3", "h4", "br"
    );

    private static final Pattern TAG_PATTERN = Pattern.compile("</?([a-zA-Z0-9]+)[^>]*>");

    /**
     * Sanitizes HTML content by keeping allowed tags and stripping disallowed tags,
     * script/style blocks, and dangerous attributes.
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Remove <script> and <style> tags and their contents completely
        String clean = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "")
                           .replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "");

        // Match tags and strip if not allowed
        Matcher matcher = TAG_PATTERN.matcher(clean);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String tagName = matcher.group(1).toLowerCase();
            if (ALLOWED_TAGS.contains(tagName)) {
                // Keep clean tag without unsafe attributes (preserve clean tag shape)
                String fullMatch = matcher.group(0);
                boolean isClosing = fullMatch.startsWith("</");
                if (isClosing) {
                    matcher.appendReplacement(sb, "</" + tagName + ">");
                } else {
                    matcher.appendReplacement(sb, "<" + tagName + ">");
                }
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}
