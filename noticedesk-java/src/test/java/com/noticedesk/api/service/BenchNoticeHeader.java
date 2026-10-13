package com.noticedesk.api.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Header fields the live pipeline runner reads from a bench notice's OCR text. Understands both header
 * styles used by the test benches:
 * <ul>
 *   <li>the committed dummy testbench: {@code Reference No: SCN/MH/2023-24/606}, {@code Date: 18-09-2023},
 *       {@code Financial Year: 2022-23}, {@code Show Cause Notice under Section 73 of ...};</li>
 *   <li>the private set: {@code Reference No.: [____________]      Dated: 24.05.2024}, a subject line with
 *       {@code FY 2019-20}, {@code Show cause notice under Section 73(1) ...}.</li>
 * </ul>
 * A bracket / underscore placeholder such as {@code [____________]} means "value not present" and is
 * returned as {@code null}, never as a value. Every accessor returns {@code null} when nothing is found.
 */
final class BenchNoticeHeader {

    private BenchNoticeHeader() {}

    /** All header fields of one notice; any of them may be {@code null}. */
    record Header(String reference, String date, String clientName, String gstin, String financialYear, String section) {}

    static Header parse(String ocr) {
        return new Header(reference(ocr), date(ocr), clientName(ocr), gstin(ocr), financialYear(ocr), section(ocr));
    }

    private static final Pattern REFERENCE = Pattern.compile("(?i)Reference No\\.?\\s*:\\s*(\\S+)");
    /** "Date:" / "Dated:" at a line start or after a run of spaces (so "Due Date:" is not taken). */
    private static final Pattern DATE = Pattern.compile(
            "(?i)(?:^|\\n|\\s{2,}|\\t)\\s*Dated?\\s*:\\s*(\\d{2})[-./](\\d{2})[-./](\\d{4})");
    private static final Pattern CLIENT = Pattern.compile("To,\\s*\\n(M/s [^\\n]+)");
    private static final Pattern GSTIN = Pattern.compile("GSTIN:\\s*([0-9A-Z]{15})");
    private static final Pattern FY_LABEL = Pattern.compile("(?i)Financial Year\\s*:\\s*(\\d{4}\\s*-\\s*\\d{2,4})");
    private static final Pattern FY_SHORT = Pattern.compile("(?i)\\bF\\.?\\s?Y\\.?\\s*(\\d{4}\\s*-\\s*\\d{2,4})");
    private static final Pattern SECTION_NOTICE = Pattern.compile(
            "(?i)(?:show\\s+cause\\s+notice|notice)\\s+under\\s+Section\\s+(\\d+[A-Z]?)\\b");
    private static final Pattern SECTION_ANY = Pattern.compile("(?i)\\bunder\\s+Section\\s+(\\d+[A-Z]?)\\b");
    /** Only brackets, underscores, dashes, dots or spaces: a form placeholder, not a value. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^[\\[\\]_\\-.\\s]*$");

    /** "Reference No:" or "Reference No.:" value; a placeholder gives {@code null}. */
    static String reference(String ocr) {
        return valueOrNull(find(REFERENCE, ocr, 1));
    }

    /** "Date:" or "Dated:" followed by dd-mm-yyyy or dd.mm.yyyy, normalised to dd-mm-yyyy. */
    static String date(String ocr) {
        if (ocr == null) return null;
        Matcher m = DATE.matcher(ocr);
        if (!m.find()) return null;
        return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
    }

    /** The "M/s ..." line after "To,". */
    static String clientName(String ocr) {
        return valueOrNull(find(CLIENT, ocr, 1));
    }

    /** A full 15-character GSTIN; a placeholder has no 15-character value, so it gives {@code null}. */
    static String gstin(String ocr) {
        return valueOrNull(find(GSTIN, ocr, 1));
    }

    /** "Financial Year: 2022-23", else the first "FY 2019-20" / "F.Y. 2019-20"; normalised to "2019-20". */
    static String financialYear(String ocr) {
        String raw = find(FY_LABEL, ocr, 1);
        if (raw == null) raw = find(FY_SHORT, ocr, 1);
        if (raw == null) return null;
        String compact = raw.replaceAll("\\s+", "");
        int dash = compact.indexOf('-');
        String start = compact.substring(0, dash);
        String end = compact.substring(dash + 1);
        if (end.length() == 4) end = end.substring(2);
        return start + "-" + end;
    }

    /**
     * "Show cause notice under Section 73(1)", "Notice under Section 74", else the first "under Section 73 of":
     * always "Section N" with any sub-section in brackets stripped.
     */
    static String section(String ocr) {
        String number = find(SECTION_NOTICE, ocr, 1);
        if (number == null) number = find(SECTION_ANY, ocr, 1);
        return number == null ? null : "Section " + number.toUpperCase(Locale.ROOT);
    }

    /** True for blank text or a form placeholder such as "[____________]". */
    static boolean isPlaceholder(String value) {
        return value == null || PLACEHOLDER.matcher(value.trim()).matches();
    }

    private static String find(Pattern pattern, String text, int group) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(group).trim() : null;
    }

    private static String valueOrNull(String value) {
        return isPlaceholder(value) ? null : value;
    }
}
