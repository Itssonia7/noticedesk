package com.noticedesk.api.service.layout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The facts the filing layout needs that the 15 draft sections do not carry in a parseable form: the letter header
 * (officer, office, client address), a plain title and amount per issue, and the stance of every notice paragraph.
 * Written by the assembly as one HTML comment in Section 13 ({@link #toComment}), so a stored draft can be laid out
 * again; the comment is invisible to the checks and to the HTML renderers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LayoutMeta(
        String docLabel,
        String noticeType,
        String noticeRef,
        String noticeDate,
        String noticeSection,
        String lawName,
        String period,
        String officer,
        List<String> officeLines,
        String clientName,
        List<String> clientAddress,
        String gstin,
        String totalDemand,
        List<IssueMeta> issues,
        List<ParaMeta> paras
) {
    public LayoutMeta {
        officeLines = officeLines != null ? officeLines : List.of();
        clientAddress = clientAddress != null ? clientAddress : List.of();
        issues = issues != null ? issues : List.of();
        paras = paras != null ? paras : List.of();
    }

    /** Issue kinds of {@link IssueMeta#kind}. */
    public static final String KIND_DEMAND = "demand";
    public static final String KIND_DEFECT = "defect";
    public static final String KIND_CONSEQUENTIAL = "consequential";

    /**
     * One issue as the reply names it: "Issue {no}: {title} (Rs {amount})". {@code writerNote} is why the issue has no
     * drafted text (e.g. "AI draft skipped (cost cap)"); {@code position} is the one-line position for the summary table.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueMeta(int no, String title, String amount, String kind, Integer parentNo, String writerNote, String position) {}

    /** Stances of {@link ParaMeta#stance}. */
    public static final String DENIED = "denied";
    public static final String ADMITTED = "admitted";
    public static final String NOTED = "noted";
    public static final String PARTNER = "partner";

    /** A notice paragraph and the reply it gets: denied with the issues that answer it, admitted, noted, or for the partner. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParaMeta(int no, String stance, List<Integer> issues) {
        public ParaMeta {
            issues = issues != null ? issues : List.of();
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String OPEN = "<!--nd-layout ";
    private static final String CLOSE = " nd-layout-->";
    private static final Pattern COMMENT = Pattern.compile(Pattern.quote(OPEN) + "(.*?)" + Pattern.quote(CLOSE), Pattern.DOTALL);

    /**
     * The metadata as one HTML comment. '<', '>' and '-' inside the JSON are written as \\u escapes so the comment never
     * ends early and "&lt;!--[^>]*--&gt;" strippers remove it whole; names stay readable (a restorer that replaces
     * anonymised names in the draft text replaces them here too).
     */
    public String toComment() {
        try {
            String json = OM.writeValueAsString(this)
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("-", "\\u002d");
            return OPEN + json + CLOSE;
        } catch (Exception e) {
            throw new IllegalStateException("layout metadata not serialisable", e);
        }
    }

    /** The metadata in {@code html} (the first nd-layout comment), or null when there is none or it cannot be read. */
    public static LayoutMeta fromHtml(String html) {
        if (html == null) return null;
        Matcher m = COMMENT.matcher(html);
        if (!m.find()) return null;
        try {
            return OM.readValue(m.group(1), LayoutMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code html} without the metadata comment. */
    public static String strip(String html) {
        return html == null ? null : COMMENT.matcher(html).replaceAll("");
    }
}
