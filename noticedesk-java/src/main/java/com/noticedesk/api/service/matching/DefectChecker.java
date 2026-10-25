package com.noticedesk.api.service.matching;

import com.noticedesk.api.service.matching.StageResolver.DocumentKind;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procedural defects tested by code after matching: date arithmetic and form checks the matcher found in some runs and
 * missed in others on the same input. Each check returns defect, ok or cannot_test with a short basis naming the dates
 * used; the matcher's {@code defect_checks} stay as a second opinion ({@link CodeDefectMerger}). Pure: no network, no
 * database.
 * <ul>
 *   <li>{@link #LIMITATION_73}: Section 73 SCN dated after the SCN deadline of its financial year ({@link DefectDeadlines}).</li>
 *   <li>{@link #PREMATURE_74}: Section 74 SCN dated before the GSTR-9 due date of its financial year.</li>
 *   <li>{@link #DIN}: central-tax document dated on or after 08.11.2019 without a DIN / RFN value.</li>
 *   <li>{@link #SIGNATURE}: no signature evidence in the last 25 lines and a blank signature block (or "does not
 *       require signature").</li>
 *   <li>{@link #DRC01A_REPLY_TIME}: DRC-01A giving fewer than the configured days to reply.</li>
 * </ul>
 */
public final class DefectChecker {

    public static final String LIMITATION_73 = "limitation_73";
    public static final String PREMATURE_74 = "premature_74";
    public static final String DIN = "din";
    public static final String SIGNATURE = "signature";
    public static final String DRC01A_REPLY_TIME = "drc01a_reply_time";

    public static final String RESULT_DEFECT = "defect";
    public static final String RESULT_OK = "ok";
    public static final String RESULT_CANNOT_TEST = "cannot_test";

    /** Basis prefix of an ok result for a check that does not apply to the document (never disputes the matcher). */
    public static final String NOT_APPLICABLE = "not applicable: ";

    /** The matcher's {@code defect_checks} entry each code check corresponds to. */
    public static final Map<String, String> MATCHER_CHECK = Map.of(
            LIMITATION_73, "limitation",
            PREMATURE_74, "premature_74",
            DIN, "din_rfn",
            SIGNATURE, "signature",
            DRC01A_REPLY_TIME, "reply_time");

    /** Default minimum days between a DRC-01A and its Part B / reply date (drafting.defects.drc01a-min-days). */
    public static final int DEFAULT_DRC01A_MIN_DAYS = 15;

    static final int BASIS_MAX_WORDS = 20;
    static final int SIGNATURE_LINES = 25;
    private static final int HEAD_LINES = 15;

    /**
     * One code check. {@code result} is {@link #RESULT_DEFECT}, {@link #RESULT_OK} or {@link #RESULT_CANNOT_TEST};
     * {@code basis} is at most 20 words with the dates used; the card ids are null when no catalogue card covers the
     * defect at that stage.
     */
    public record CodeDefect(String check, String result, String basis, String cardIdReply, String cardIdAppeal) {
        public CodeDefect {
            basis = limitWords(basis);
        }

        public boolean isDefect() {
            return RESULT_DEFECT.equals(result);
        }

        /** An ok result for a check that applied to the document (not a "not applicable" ok). */
        public boolean isTestedOk() {
            return RESULT_OK.equals(result) && !basis.startsWith(NOT_APPLICABLE);
        }

        /** The card for the response stage: the appeal card at the appeal stage, else the reply card. */
        public String cardFor(boolean appealStage) {
            return appealStage ? cardIdAppeal : cardIdReply;
        }

        /** The check's cards (reply, appeal), without nulls. */
        public List<String> cards() {
            List<String> out = new ArrayList<>();
            if (cardIdReply != null) out.add(cardIdReply);
            if (cardIdAppeal != null && !cardIdAppeal.equals(cardIdReply)) out.add(cardIdAppeal);
            return out;
        }

        /** The matcher's {@code defect_checks} name for this check. */
        public String matcherCheck() {
            return MATCHER_CHECK.get(check);
        }
    }

    /**
     * What the checks read. The notice block fields come from the matcher (null when it did not read them, in which
     * case the text is read by regex); {@code stage} is the {@link StageDetector} stage (scn_73 / scn_74 / scn_74a ...)
     * and {@code responseStage} the {@link StageResolver} stage.
     */
    public record Input(String text, String issueDate, String financialYear, String noticeNumber, String din,
                        String documentType, String stage, ResponseStage responseStage) {}

    private final int drc01aMinDays;

    public DefectChecker() {
        this(DEFAULT_DRC01A_MIN_DAYS);
    }

    public DefectChecker(int drc01aMinDays) {
        this.drc01aMinDays = drc01aMinDays > 0 ? drc01aMinDays : DEFAULT_DRC01A_MIN_DAYS;
    }

    /** All five checks, in a fixed order. */
    public List<CodeDefect> check(Input in) {
        String text = in.text() != null ? in.text() : "";
        LocalDate date = documentDate(in.issueDate(), text);
        Integer fy = financialYearStart(in.financialYear(), text);
        DocumentKind kind = StageResolver.documentKind(in.documentType(), text);
        String section = scnSection(in.stage(), text);
        return List.of(
                limitation73(kind, section, date, fy),
                premature74(kind, section, date, fy),
                din(text, in.din(), date),
                signature(text),
                drc01aReplyTime(in.documentType(), text, date));
    }

    /**
     * The reply-stage and appeal-stage card ids of every check that applies to the document (a result other than a
     * "not applicable" ok), in check order without repeats: the cards the shortlist always shows in full, so a defect
     * the code finds always has its card in front of the matcher. Read from the text alone (no matcher fields).
     */
    public static List<String> applicableCardIds(String documentType, String stage, ResponseStage responseStage, String text) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (CodeDefect d : new DefectChecker().check(new Input(text, null, null, null, null, documentType, stage, responseStage))) {
            if (d.basis().startsWith(NOT_APPLICABLE)) continue;
            out.addAll(d.cards());
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ checks

    static CodeDefect limitation73(DocumentKind kind, String section, LocalDate date, Integer fy) {
        String reply = fy != null && DefectDeadlines.NDK_105_FYS.contains(fy) ? "NDK-105" : null;
        if (kind != DocumentKind.SCN || !"73".equals(section)) {
            return new CodeDefect(LIMITATION_73, RESULT_OK, NOT_APPLICABLE + "not a Section 73 SCN", reply, null);
        }
        if (date == null || fy == null) {
            return new CodeDefect(LIMITATION_73, RESULT_CANNOT_TEST,
                    (date == null ? "notice date" : "financial year") + " not stated", reply, null);
        }
        LocalDate deadline = DefectDeadlines.SCN_73_DEADLINE.get(fy);
        if (deadline == null) {
            return new CodeDefect(LIMITATION_73, RESULT_CANNOT_TEST, "no Section 73 SCN deadline in the table for FY " + fyLabel(fy),
                    reply, null);
        }
        String basis = "SCN " + fmt(date) + " vs Section 73 SCN deadline " + fmt(deadline) + " for FY " + fyLabel(fy);
        return new CodeDefect(LIMITATION_73, date.isAfter(deadline) ? RESULT_DEFECT : RESULT_OK,
                basis + (date.isAfter(deadline) ? ": time-barred" : ""), reply, null);
    }

    static CodeDefect premature74(DocumentKind kind, String section, LocalDate date, Integer fy) {
        if (kind != DocumentKind.SCN || !"74".equals(section)) {
            return new CodeDefect(PREMATURE_74, RESULT_OK, NOT_APPLICABLE + "not a Section 74 SCN", "NDK-104", null);
        }
        if (date == null || fy == null) {
            return new CodeDefect(PREMATURE_74, RESULT_CANNOT_TEST, (date == null ? "notice date" : "financial year") + " not stated",
                    "NDK-104", null);
        }
        LocalDate due = DefectDeadlines.GSTR9_DUE_DATE.get(fy);
        if (due == null) {
            return new CodeDefect(PREMATURE_74, RESULT_CANNOT_TEST, "no GSTR-9 due date in the table for FY " + fyLabel(fy), "NDK-104", null);
        }
        boolean premature = date.isBefore(due);
        return new CodeDefect(PREMATURE_74, premature ? RESULT_DEFECT : RESULT_OK,
                "SCN " + fmt(date) + " vs GSTR-9 due date " + fmt(due) + " for FY " + fyLabel(fy) + (premature ? ": premature" : ""),
                "NDK-104", null);
    }

    private static final Pattern ACT_NAME = Pattern.compile(
            "(?i)\\b(central\\s+goods\\s+and\\s+services\\s+tax|CGST)(\\s*/\\s*[A-Z]+)?\\s+(act|rules)\\b");
    private static final Pattern CENTRAL_STATE = Pattern.compile(
            "(?i)\\bcentral\\s*/\\s*state\\s+tax\\b|\\bstate\\s*/\\s*central\\s+tax\\b|\\bCGST\\s*/\\s*[A-Z]{1,3}GST\\b|\\b[A-Z]{1,3}GST\\s*/\\s*CGST\\b");
    private static final Pattern CENTRAL = Pattern.compile(
            "(?i)\\bcentral\\s+tax\\b|\\bCGST\\b|\\bcentral\\s+goods\\s+and\\s+services\\s+tax\\b");
    private static final Pattern STATE = Pattern.compile(
            "(?i)\\bstate\\s+tax\\b|\\bSGST\\b|\\bstate\\s+GST\\b|\\bcommercial\\s+tax(es)?\\b|\\bgovernment\\s+of\\s+(?!india\\b)[a-z]+");
    private static final Pattern DESIGNATION = Pattern.compile(
            "(?i)superintendent|commissioner|officer|inspector|assistant|deputy|joint|additional");
    private static final Pattern DIN_LINE = Pattern.compile(
            "(?im)^[^\\n]*?\\b(?:DIN|RFN)\\b\\s*(?:no\\.?|number)?\\s*[:\\-–]?([^\\n]*)$");
    /** A real DIN / RFN value: a token of 8 or more letters / digits with at least one digit. */
    private static final Pattern DIN_VALUE = Pattern.compile("(?i)(?<![A-Z0-9])(?=[A-Z0-9/-]*\\d)[A-Z0-9/-]{8,}");

    static CodeDefect din(String text, String matcherDin, LocalDate date) {
        String head = head(text);
        String designations = String.join("\n", lastLines(text, SIGNATURE_LINES).stream()
                .filter(l -> DESIGNATION.matcher(l).find()).toList());
        String scope = ACT_NAME.matcher(head + "\n" + designations).replaceAll(" ");
        boolean ambiguous = CENTRAL_STATE.matcher(scope).find();
        String rest = CENTRAL_STATE.matcher(scope).replaceAll(" ");
        boolean central = CENTRAL.matcher(rest).find();
        if (!central) {
            if (ambiguous) {
                return new CodeDefect(DIN, RESULT_CANNOT_TEST, "header says Central / State Tax; no central-tax officer designation",
                        "NDK-141", "NDK-142");
            }
            if (STATE.matcher(rest).find()) {
                return new CodeDefect(DIN, RESULT_OK, NOT_APPLICABLE + "State GST document", "NDK-141", "NDK-142");
            }
            return new CodeDefect(DIN, RESULT_CANNOT_TEST, "issuing authority not stated in the header", "NDK-141", "NDK-142");
        }
        if (date == null) {
            return new CodeDefect(DIN, RESULT_CANNOT_TEST, "central-tax document; date not stated", "NDK-141", "NDK-142");
        }
        if (date.isBefore(DefectDeadlines.DIN_MANDATORY_FROM)) {
            return new CodeDefect(DIN, RESULT_OK, NOT_APPLICABLE + "dated " + fmt(date) + ", before 08.11.2019", "NDK-141", "NDK-142");
        }
        if (matcherDin != null && DIN_VALUE.matcher(matcherDin).find()) {
            return new CodeDefect(DIN, RESULT_OK, "central-tax document dated " + fmt(date) + " carries a DIN", "NDK-141", "NDK-142");
        }
        Matcher m = DIN_LINE.matcher(text);
        boolean line = false;
        while (m.find()) {
            line = true;
            if (DIN_VALUE.matcher(m.group(1)).find()) {
                return new CodeDefect(DIN, RESULT_OK, "central-tax document dated " + fmt(date) + " carries a DIN", "NDK-141", "NDK-142");
            }
        }
        return new CodeDefect(DIN, RESULT_DEFECT, "central-tax document dated " + fmt(date)
                + (line ? ": DIN field blank or a placeholder" : ": no DIN line") + ", on/after 08.11.2019", "NDK-141", "NDK-142");
    }

    private static final Pattern SIGNATURE_EVIDENCE = Pattern.compile(
            "(?i)digitally\\s+signed|\\bsd\\s*/-|signature\\s+valid|\\be-?signed\\b|\\bsigned\\s+by\\s*[:\\-]?\\s*[A-Z][A-Za-z.]+");
    private static final Pattern BLANK_SIGNATURE_LINE = Pattern.compile(
            "(?i)^\\s*\\(?(signature)?\\)?\\s*[:\\-]?\\s*[_.]{5,}\\s*$");
    private static final Pattern EMPTY_SIGNATURE_FIELD = Pattern.compile(
            "(?i)\\bsignature\\s*:\\s*(\\[?[_\\s.]*\\]?)\\s*(name\\s*:.*)?$");
    private static final Pattern EMPTY_NAME_FIELD = Pattern.compile(
            "(?i)\\bname\\s*:\\s*(\\[?[_\\s.]*\\]?)\\s*(designation\\s*:.*)?$");
    private static final Pattern NO_SIGNATURE_NEEDED = Pattern.compile("(?i)does\\s+not\\s+require\\s+(a\\s+|any\\s+)?signature");

    static CodeDefect signature(String text) {
        if (text == null || text.isBlank()) {
            return new CodeDefect(SIGNATURE, RESULT_CANNOT_TEST, "document text not available", "NDK-143", "NDK-144");
        }
        List<String> tail = lastLines(text, SIGNATURE_LINES);
        for (String l : tail) {
            if (SIGNATURE_EVIDENCE.matcher(l).find()) {
                return new CodeDefect(SIGNATURE, RESULT_OK, "signature evidence: " + l.trim(), "NDK-143", "NDK-144");
            }
        }
        if (NO_SIGNATURE_NEEDED.matcher(text).find()) {
            return new CodeDefect(SIGNATURE, RESULT_DEFECT, "states it does not require signature; no digital signature",
                    "NDK-143", "NDK-144");
        }
        for (String l : tail) {
            if (BLANK_SIGNATURE_LINE.matcher(l).find() || EMPTY_SIGNATURE_FIELD.matcher(l).find() || EMPTY_NAME_FIELD.matcher(l).find()) {
                return new CodeDefect(SIGNATURE, RESULT_DEFECT, "signature block blank (" + l.trim() + "); no digital signature",
                        "NDK-143", "NDK-144");
            }
        }
        return new CodeDefect(SIGNATURE, RESULT_OK, "no blank signature block in the last " + SIGNATURE_LINES + " lines",
                "NDK-143", "NDK-144");
    }

    private static final Pattern DRC01A = Pattern.compile("(?i)\\bDRC[\\s_\\-]*0?1A\\b");
    private static final String DATE = "(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})";
    private static final Pattern REPLY_DATE = Pattern.compile(
            "(?i)(part\\s*b|reply|respon\\w*|submi\\w*|pay\\w*|on\\s+or\\s+before|latest\\s+by)[^\\n]{0,120}?" + DATE);
    private static final Pattern WITHIN_DAYS = Pattern.compile("(?i)within\\s+(\\d{1,3})\\s+days");

    CodeDefect drc01aReplyTime(String documentType, String text, LocalDate date) {
        boolean drc01a = (documentType != null && DRC01A.matcher(documentType).find())
                || DRC01A.matcher(text.substring(0, Math.min(StageResolver.HEADING_CHARS, text.length()))).find();
        if (!drc01a) {
            return new CodeDefect(DRC01A_REPLY_TIME, RESULT_OK, NOT_APPLICABLE + "not a DRC-01A", "NDK-148", null);
        }
        if (date == null) {
            return new CodeDefect(DRC01A_REPLY_TIME, RESULT_CANNOT_TEST, "DRC-01A date not stated", "NDK-148", null);
        }
        Long days = null;
        String replyBy = null;
        Matcher m = REPLY_DATE.matcher(text);
        while (m.find()) {
            LocalDate d = parseDate(m.group(2));
            if (d != null && d.isAfter(date)) {
                days = ChronoUnit.DAYS.between(date, d);
                replyBy = "reply by " + fmt(d);
                break;
            }
        }
        if (days == null) {
            Matcher w = WITHIN_DAYS.matcher(text);
            if (w.find()) {
                days = Long.parseLong(w.group(1));
                replyBy = "reply within " + days + " days";
            }
        }
        if (days == null) {
            return new CodeDefect(DRC01A_REPLY_TIME, RESULT_CANNOT_TEST, "DRC-01A " + fmt(date) + ": no Part B / reply date stated",
                    "NDK-148", null);
        }
        boolean shortTime = days < drc01aMinDays;
        return new CodeDefect(DRC01A_REPLY_TIME, shortTime ? RESULT_DEFECT : RESULT_OK,
                "DRC-01A " + fmt(date) + ", " + replyBy + ": " + days + " days " + (shortTime ? "<" : ">=") + " " + drc01aMinDays,
                "NDK-148", null);
    }

    // ------------------------------------------------------------------ reading the document

    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)");
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern DATE_LABEL = Pattern.compile("(?im)\\bdated?\\b\\s*[:\\-]?\\s*" + DATE);
    private static final Pattern FY = Pattern.compile("(20\\d{2})\\s*[-–/]\\s*(\\d{2,4})");
    private static final Pattern FY_IN_TEXT = Pattern.compile(
            "(?i)(?:\\bF\\.?\\s*Y\\.?|\\bfinancial\\s+year)\\s*[:\\-]?\\s*(20\\d{2})\\s*[-–/]\\s*(\\d{2,4})");
    private static final Pattern SECTION_IN_TEXT = Pattern.compile("(?i)\\bsection\\s*(73|74)\\s*\\(1\\)|\\bsection\\s*(74\\s?A)\\b");

    /** dd.mm.yyyy / dd-mm-yyyy / dd/mm/yyyy (or yyyy-mm-dd) anywhere in {@code s}; null when none or invalid. */
    static LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            Matcher m = DMY.matcher(s);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            m = ISO.matcher(s);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }

    /** The matcher's issue date, else the first "Date: ..." / "Dated ..." in the text, else the first date in the header. */
    static LocalDate documentDate(String issueDate, String text) {
        LocalDate d = parseDate(issueDate);
        if (d != null || text == null) return d;
        Matcher m = DATE_LABEL.matcher(text);
        if (m.find()) {
            d = parseDate(m.group(1));
            if (d != null) return d;
        }
        return parseDate(head(text));
    }

    /** Start year of the financial year ("2019-20", "FY 2019-20", "Financial Year 2019-2020" -> 2019), matcher first. */
    public static Integer financialYearStart(String financialYear, String text) {
        Integer fy = fyStart(financialYear != null ? FY.matcher(financialYear) : null);
        if (fy != null || text == null) return fy;
        return fyStart(FY_IN_TEXT.matcher(text));
    }

    private static Integer fyStart(Matcher m) {
        if (m == null || !m.find()) return null;
        int start = Integer.parseInt(m.group(1));
        String end = m.group(2);
        int endYear = end.length() == 2 ? (start / 100) * 100 + Integer.parseInt(end) : Integer.parseInt(end);
        return endYear == start + 1 ? start : null;
    }

    /** "73", "74" or "74A" from the StageDetector stage, else from "Section 73(1)" / "Section 74(1)" / "Section 74A" in the text. */
    static String scnSection(String stage, String text) {
        if (stage != null) {
            switch (stage.toLowerCase(Locale.ROOT)) {
                case "scn_73": return "73";
                case "scn_74": return "74";
                case "scn_74a": return "74A";
                default: break;
            }
        }
        if (text == null) return null;
        Matcher m = SECTION_IN_TEXT.matcher(text);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : "74A";
    }

    /** The header: the lines before "To," (at most the first 15 lines). */
    static String head(String text) {
        if (text == null) return "";
        List<String> lines = Arrays.asList(text.split("\\R"));
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (out.size() >= HEAD_LINES || l.trim().matches("(?i)to\\s*,?")) break;
            out.add(l);
        }
        return String.join("\n", out);
    }

    /** The last {@code n} non-blank lines. */
    static List<String> lastLines(String text, int n) {
        if (text == null) return List.of();
        List<String> lines = Arrays.stream(text.split("\\R")).filter(l -> !l.isBlank()).toList();
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }

    private static final DateTimeFormatter DMY_OUT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    static String fmt(LocalDate d) {
        return d.format(DMY_OUT);
    }

    public static String fyLabel(int start) {
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** At most {@link #BASIS_MAX_WORDS} words. */
    static String limitWords(String s) {
        if (s == null) return "";
        String[] words = s.trim().split("\\s+");
        if (words.length <= BASIS_MAX_WORDS) return s.trim();
        return String.join(" ", Arrays.copyOf(words, BASIS_MAX_WORDS));
    }
}
