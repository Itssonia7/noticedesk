package com.noticedesk.api.service.layout;

import com.noticedesk.api.service.matching.TemplateFillService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The partner-approved filing layout (agreed 16-11-2026): the REPLY for filing, then a page break and the INTERNAL
 * REVIEW SHEET - NOT FOR FILING. Both are lists of {@link Block}s rendered as text ({@link ReplyLayoutText}) and as
 * Word ({@code DocxExportService#renderReplyDocx}). Text in a block is plain; a placeholder is a short bracketed phrase
 * wrapped in {@link #HL_START} / {@link #HL_END} so the Word renderer can print it bold on yellow.
 */
public record ReplyLayout(List<Block> reply, List<Block> review) {

    public static final String REVIEW_TITLE = "INTERNAL REVIEW SHEET - NOT FOR FILING";

    /** Start / end of a highlighted placeholder phrase inside block text (private-use characters, never in a notice). */
    public static final char HL_START = '';
    public static final char HL_END = '';

    /** Paragraph styles. */
    public enum Style {
        /** Addresses and other letter lines: left aligned, no spacing rules; an empty one is a blank line. */
        LETTER,
        /** The firm letterhead line: centred. */
        LETTERHEAD,
        /** The "Date:" line of the letter: right aligned in Word. */
        DATE,
        /** The bold subject line. */
        SUBJECT,
        /** Document title (the review sheet title), centred bold. */
        TITLE,
        /** A Part heading ("PART B: ISSUE-WISE REPLY"), bold with a thin rule under it. */
        PART,
        /** An issue heading ("Issue 1: ... (Rs ...)"), bold. */
        HEADING,
        /** A sub-heading ("Facts", "Legal submissions"), bold italic. */
        SUBHEADING,
        /** A justified body paragraph; with a number it is printed with a hanging indent. */
        BODY,
        /** A list item; with a number ("(a)") it is printed with a hanging indent. */
        ITEM,
        /** Signature block lines: left aligned. */
        SIGNATURE
    }

    public sealed interface Block permits Para, Table, PageBreak {}

    /** A paragraph; {@code number} ("3.1", "(a)") is printed before the text with a hanging indent, null for none. */
    public record Para(Style style, String number, String text) implements Block {
        public static Para of(Style style, String text) {
            return new Para(style, null, text);
        }
    }

    /** A table with a header row; the columns in {@code rightAligned} are right aligned (amounts). */
    public record Table(List<String> header, List<List<String>> rows, Set<Integer> rightAligned) implements Block {}

    public record PageBreak() implements Block {}

    /** Every text of the layout passed through {@code f} (a de-anonymising restorer). */
    public ReplyLayout mapText(Function<String, String> f) {
        return new ReplyLayout(map(reply, f), map(review, f));
    }

    private static List<Block> map(List<Block> blocks, Function<String, String> f) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b instanceof Para p) {
                out.add(new Para(p.style(), p.number() != null ? f.apply(p.number()) : null, p.text() != null ? f.apply(p.text()) : null));
            } else if (b instanceof Table t) {
                List<List<String>> rows = new ArrayList<>();
                for (List<String> r : t.rows()) rows.add(r.stream().map(c -> c != null ? f.apply(c) : null).toList());
                out.add(new Table(t.header().stream().map(f).toList(), rows, t.rightAligned()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ placeholders and numbers

    /** Short human phrases for variable names that read badly with only the underscores replaced. */
    static final Map<String, String> LABELS = Map.of(
            "rule86b_exception", "which exception",
            "client_gstin", "GSTIN",
            "notice_number", "notice reference",
            "issue_date", "notice date",
            "amount_tax", "amount of tax",
            "para_map", "paragraph-wise reply");

    private static final Pattern DRAFT_MARKER = Pattern.compile(
            "\\[\\[\\s*(MISSING|PARTNER|VERIFY|CONFIRM|PENDING_AI)\\b\\s*:?\\s*([^\\]]*)\\]\\]");
    private static final Pattern CURLY = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");
    /** "Rs 11200000", "Rs. 4494680.00", "INR 250000" without grouping commas. */
    private static final Pattern UNGROUPED_AMOUNT = Pattern.compile("(?i)\\b(Rs\\.?|INR)\\s*(\\d{4,})(?:\\.(\\d{1,2}))?(?![\\d,])");

    /** A highlighted bracketed phrase. */
    public static String hl(String phrase) {
        return HL_START + "[" + phrase + "]" + HL_END;
    }

    /** "date_of_receipt" / "issue.scrip_type" -> "date of receipt" / "scrip type" (or the label of {@link #LABELS}). */
    public static String phrase(String variable) {
        String v = variable == null ? "" : variable.trim();
        int dot = v.lastIndexOf('.');
        if (dot >= 0 && dot < v.length() - 1 && v.matches("[A-Za-z_]+\\.[A-Za-z0-9_.]+")) v = v.substring(dot + 1);
        String label = LABELS.get(v.toLowerCase());
        if (label != null) return label;
        return v.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * {@code text} with every draft marker and template variable as a short highlighted phrase: [[MISSING: date_of_receipt]]
     * -> [date of receipt], [[VERIFY ...]] -> [VERIFY: ...], [[CONFIRM: x]] -> [CONFIRM: x], [[PARTNER: x]] -> [x],
     * [[PENDING_AI: Issue 3]] -> [Partner to draft: Issue 3], {{issue.scrip_type}} -> [scrip type]; and every
     * ungrouped rupee amount in Indian format (Rs 1,12,00,000).
     */
    public static String humanise(String text) {
        if (text == null) return "";
        Matcher m = DRAFT_MARKER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String kind = m.group(1).toUpperCase();
            String body = m.group(2).trim();
            String phrase = switch (kind) {
                case "MISSING" -> phrase(body);
                case "VERIFY" -> "VERIFY: " + body;
                case "CONFIRM" -> "CONFIRM: " + body;
                case "PENDING_AI" -> "Partner to draft: " + body;
                default -> partnerPhrase(body);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(hl(phrase.isBlank() ? "partner to complete" : phrase)));
        }
        m.appendTail(sb);
        Matcher c = CURLY.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (c.find()) {
            String key = c.group(1);
            String phrase = key.startsWith("ref:") ? "paragraph reference" : phrase(key);
            c.appendReplacement(sb2, Matcher.quoteReplacement(hl(phrase)));
        }
        c.appendTail(sb2);
        return indianAmounts(sb2.toString());
    }

    /** A [[PARTNER]] body as a phrase: the skipped / failed AI draft wording of the approved layout, else the body. */
    static String partnerPhrase(String body) {
        String b = body.trim();
        String lower = b.toLowerCase();
        if (lower.startsWith("ai draft skipped")) {
            String reason = b.replaceFirst("(?i)^ai draft skipped\\s*[—–-]?\\s*", "").trim();
            return "Partner to draft: the AI draft for this issue was skipped (" + (reason.isEmpty() ? "no reason given" : reason) + ")";
        }
        if (lower.startsWith("ai draft failed")) {
            return "Partner to draft: the AI draft for this issue failed";
        }
        return b;
    }

    /** Ungrouped rupee amounts in Indian format; "Rs." and "INR" become "Rs". */
    public static String indianAmounts(String text) {
        if (text == null) return null;
        Matcher m = UNGROUPED_AMOUNT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String formatted = TemplateFillService.formatIndianCurrency(new BigDecimal(m.group(2)));
            if (m.group(3) != null && !m.group(3).matches("0+")) formatted += "." + m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(formatted));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code text} without the highlight markers. */
    public static String plain(String text) {
        return text == null ? "" : text.replace(String.valueOf(HL_START), "").replace(String.valueOf(HL_END), "");
    }
}
