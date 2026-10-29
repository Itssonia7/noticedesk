package com.noticedesk.api.service.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Header facts read by code from the document text after matching, so the draft never depends on the matcher (or a
 * header regex) picking the right value out of a notice that names several parties:
 * <ul>
 *   <li>{@link #taxpayerGstin}: the noticee's GSTIN (the first GSTIN within {@link #PARTY_WINDOW} characters after a
 *       party designation in the first {@link #HEAD_CHARS} characters, else the first GSTIN in the document); every
 *       other GSTIN of the text is another party's;</li>
 *   <li>{@link #taxpayerLegalName}: the noticee's legal name, read next to the taxpayer GSTIN (never a supplier's);</li>
 *   <li>{@link #financialYearsOf} / {@link #financialYearLabel}: the financial year(s) (April to March) of a tax period;</li>
 *   <li>{@link #stateForGstin}: the State / UT of a GSTIN's state code (never "Centre Jurisdiction");</li>
 *   <li>{@link #authorityKind}: "State Tax" or "Central Tax" from the heading;</li>
 *   <li>{@link #isRefundDocument} / {@link #refundAmountFromText}: an RFD-08 refund notice and its refund amount.</li>
 * </ul>
 * Pure: no network, no database.
 */
public final class NoticeHeaderFacts {

    /** Characters of the document head searched for the party designation and the refund amount. */
    public static final int HEAD_CHARS = 1500;
    /** Characters after a party designation searched for the party's GSTIN. */
    public static final int PARTY_WINDOW = 120;

    /** Source of the chosen GSTIN: next to the party designation, the first GSTIN in the text, or none found. */
    public static final String SOURCE_PARTY = "party";
    public static final String SOURCE_FIRST = "first";
    public static final String SOURCE_NONE = "none";

    public static final String AUTHORITY_STATE = "State Tax";
    public static final String AUTHORITY_CENTRAL = "Central Tax";

    /** 2-digit state code, PAN (5 letters, 4 digits, 1 letter), entity number, Z, check character. */
    static final Pattern GSTIN = Pattern.compile("(?i)(?<![0-9A-Z])(\\d{2}[A-Z]{5}\\d{4}[A-Z][0-9A-Z]Z[0-9A-Z])(?![0-9A-Z])");
    /** "In case of M/s ...", "the noticee", "the taxpayer", "RTP", "registered person", "To,". */
    static final Pattern PARTY = Pattern.compile(
            "(?i)\\bM/s\\b\\.?|\\bnoticee\\b|\\btaxpayer\\b|\\bRTP\\b|\\bregistered\\s+person\\b|\\bTo\\s*,");

    private NoticeHeaderFacts() {}

    // ------------------------------------------------------------------ taxpayer GSTIN

    /**
     * The taxpayer's GSTIN chosen from the document text: {@code gstin} (upper case; null when the text has none), its
     * {@code source} ({@link #SOURCE_PARTY} / {@link #SOURCE_FIRST} / {@link #SOURCE_NONE}) and every other GSTIN of the
     * text in order ({@code otherParties}: suppliers, recipients; never a client field).
     */
    public record TaxpayerGstin(String gstin, String source, List<String> otherParties) {
        public TaxpayerGstin {
            otherParties = otherParties != null ? List.copyOf(otherParties) : List.of();
        }

        /** True when the GSTIN was read next to the party designation (strong evidence). */
        public boolean fromParty() {
            return SOURCE_PARTY.equals(source);
        }

        /**
         * True when the matcher gave a GSTIN that differs from the code's choice: the matcher's value is used only when
         * it is the code's candidate, so it is overridden (or dropped when the text has no GSTIN).
         */
        public boolean overrides(String matcherGstin) {
            String m = normalise(matcherGstin);
            return m != null && !m.equals(gstin);
        }
    }

    /** See {@link TaxpayerGstin}. */
    public static TaxpayerGstin taxpayerGstin(String text) {
        if (text == null || text.isBlank()) return new TaxpayerGstin(null, SOURCE_NONE, List.of());
        String party = null;
        Matcher d = PARTY.matcher(text);
        while (party == null && d.find() && d.start() < HEAD_CHARS) {
            int from = d.end();
            Matcher g = GSTIN.matcher(text);
            g.region(from, text.length());
            if (g.find() && g.start() < from + PARTY_WINDOW) party = g.group(1).toUpperCase(Locale.ROOT);
        }
        LinkedHashSet<String> all = new LinkedHashSet<>();
        Matcher g = GSTIN.matcher(text);
        while (g.find()) all.add(g.group(1).toUpperCase(Locale.ROOT));
        String chosen = party != null ? party : all.isEmpty() ? null : all.iterator().next();
        String source = party != null ? SOURCE_PARTY : chosen != null ? SOURCE_FIRST : SOURCE_NONE;
        List<String> others = new ArrayList<>(all);
        others.remove(chosen);
        return new TaxpayerGstin(chosen, source, others);
    }

    // ------------------------------------------------------------------ taxpayer legal name

    /** "Name of the RTP / taxpayer / registered person / noticee / applicant", "Legal name (of business)". */
    private static final Pattern NAME_LABEL = Pattern.compile(
            "(?i)\\b(?:legal\\s+name(?:\\s+of\\s+(?:the\\s+)?business)?|name\\s+of\\s+(?:the\\s+)?"
            + "(?:RTP|tax\\s*payer|registered\\s+person|noticee|applicant|assessee|claimant|dealer))\\s*[:\\-–]?\\s*");
    private static final Pattern MS = Pattern.compile("(?i)\\bM/s\\b\\.?\\s*");
    /** Where a name ends: a GSTIN label or value, an opening bracket, a comma / semicolon, a line break, "having", "bearing". */
    private static final Pattern NAME_END = Pattern.compile(
            "(?i)[\\n\\r(\\[,;|]|\\s[-–]\\s|\\bGSTIN\\b|\\bGST\\s*(?:No|Registration)\\b|\\bhaving\\b|\\bbearing\\b|\\bregistered\\b"
            + "|\\bAddress\\b|\\bwith\\b|\\d{2}[A-Z]{5}\\d{4}");
    /** Words that mark a sentence rather than a name ("the supplier has availed ..."). */
    private static final Set<String> NOT_IN_A_NAME = Set.of("has", "have", "is", "was", "were", "are", "availed", "supplier",
            "suppliers", "cancelled", "that", "which", "whose", "from", "dated", "refund", "notice");
    private static final int NAME_MAX_WORDS = 15;
    /** Characters between a candidate name and the taxpayer's GSTIN for the two to be read together. */
    private static final int NAME_WINDOW = 160;

    /**
     * The taxpayer's legal name from the document head (the first {@link #HEAD_CHARS} characters), read next to the
     * taxpayer's GSTIN: a labelled name ("Name of the RTP: ...", "Legal Name: ...") or an "M/s ..." name whose nearest
     * GSTIN (within {@value #NAME_WINDOW} characters, before or after it, with no other GSTIN in between) is
     * {@code taxpayerGstin}. A name next to any other GSTIN is a supplier's and never used. Null when there is no such
     * name, when two different names qualify, or when {@code taxpayerGstin} is null: the caller keeps its MISSING marker.
     * Returned without "M/s".
     */
    public static String taxpayerLegalName(String text, String taxpayerGstin) {
        String gstin = normalise(taxpayerGstin);
        if (text == null || text.isBlank() || gstin == null) return null;
        String head = text.substring(0, Math.min(HEAD_CHARS, text.length()));
        List<int[]> gstins = new ArrayList<>();   // {start, end, isTaxpayer}
        Matcher g = GSTIN.matcher(head);
        while (g.find()) gstins.add(new int[]{g.start(), g.end(), g.group(1).equalsIgnoreCase(gstin) ? 1 : 0});
        if (gstins.stream().noneMatch(x -> x[2] == 1)) return null;

        Set<String> names = new LinkedHashSet<>();
        for (Pattern p : List.of(NAME_LABEL, MS)) {
            Matcher m = p.matcher(head);
            while (m.find()) {
                String name = cleanName(head.substring(m.end()));
                if (name == null) continue;
                int nameStart = m.start();
                int nameEnd = m.end() + name.length();
                if (nearestGstinIsTaxpayer(gstins, nameStart, nameEnd)) names.add(name.toUpperCase(Locale.ROOT));
            }
        }
        return names.size() == 1 ? displayName(names.iterator().next(), head) : null;
    }

    /** True when the GSTIN closest to [start, end) within {@link #NAME_WINDOW} characters is the taxpayer's. */
    private static boolean nearestGstinIsTaxpayer(List<int[]> gstins, int start, int end) {
        int best = Integer.MAX_VALUE;
        boolean taxpayer = false;
        for (int[] x : gstins) {
            int dist = x[0] >= end ? x[0] - end : x[1] <= start ? start - x[1] : 0;
            if (dist > NAME_WINDOW) continue;
            if (dist < best) {
                best = dist;
                taxpayer = x[2] == 1;
            }
        }
        return taxpayer;
    }

    /** The name at the start of {@code s} up to {@link #NAME_END}; null when it does not read as a name. */
    private static String cleanName(String s) {
        Matcher e = NAME_END.matcher(s);
        String name = (e.find() ? s.substring(0, e.start()) : s.substring(0, Math.min(s.length(), 200)))
                .replaceAll("\\s+", " ").replaceAll("^[\\s:.\\-–]+|[\\s:.\\-–]+$", "").trim();
        name = name.replaceFirst("(?i)^M/s\\b\\.?\\s*", "");
        if (name.length() < 3 || name.length() > 150) return null;
        String[] words = name.split(" ");
        if (words.length > NAME_MAX_WORDS) return null;
        int letters = 0;
        for (String w : words) {
            if (NOT_IN_A_NAME.contains(w.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))) return null;
            if (w.chars().anyMatch(Character::isLetter)) letters++;
        }
        return letters * 2 >= words.length && Character.isLetter(name.charAt(0)) ? name : null;
    }

    /** The name in the spelling the head uses first (case kept), else as given. */
    private static String displayName(String upper, String head) {
        int at = head.toUpperCase(Locale.ROOT).indexOf(upper);
        return at >= 0 ? head.substring(at, at + upper.length()) : upper;
    }

    /** Upper case, trimmed, null when blank or not a GSTIN. */
    public static String normalise(String gstin) {
        if (gstin == null) return null;
        String g = gstin.trim().toUpperCase(Locale.ROOT);
        return GSTIN.matcher(g).matches() ? g : null;
    }

    /** True when {@code value} is a GSTIN. */
    public static boolean isGstin(String value) {
        return normalise(value) != null;
    }

    // ------------------------------------------------------------------ financial year

    /**
     * Start years of the financial years (April to March) a tax period covers ("JAN-2026", "January 2026",
     * "Jan-2026 to Mar-2026", "FY 2025-26", "2025-26"); empty when the period cannot be read.
     */
    public static List<Integer> financialYearsOf(String period) {
        Optional<PeriodNormalizer.Period> p = PeriodNormalizer.parse(period);
        if (p.isEmpty()) return List.of();
        int first = fyStart(p.get().start().getYear(), p.get().start().getMonthValue());
        int last = fyStart(p.get().end().getYear(), p.get().end().getMonthValue());
        List<Integer> out = new ArrayList<>();
        for (int y = first; y <= last && out.size() < 10; y++) out.add(y);
        return out;
    }

    /**
     * The financial year of a tax period as the notice block's {@code financial_year}: "2025-26" for one year,
     * "FY 2025-26 and FY 2026-27" for a period crossing years; null when the period cannot be read.
     */
    public static String financialYearLabel(String period) {
        return financialYearLabel(List.of(period != null ? period : ""));
    }

    /** The financial year label of several periods together (their years united), or null when none can be read. */
    public static String financialYearLabel(List<String> periods) {
        Set<Integer> years = new TreeSet<>();
        for (String p : periods) years.addAll(financialYearsOf(p));
        if (years.isEmpty()) return null;
        if (years.size() == 1) return DefectChecker.fyLabel(years.iterator().next());
        List<String> labels = new ArrayList<>();
        for (int y : years) labels.add("FY " + DefectChecker.fyLabel(y));
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }

    /** "Financial Year 2025-26", or the label itself when it already names its years ("FY 2025-26 and FY 2026-27"). */
    public static String financialYearPhrase(String fy) {
        if (fy == null) return null;
        String t = fy.trim();
        return t.regionMatches(true, 0, "FY", 0, 2) || t.regionMatches(true, 0, "F.Y", 0, 3) ? t : "Financial Year " + t;
    }

    private static int fyStart(int year, int month) {
        return month >= 4 ? year : year - 1;
    }

    // ------------------------------------------------------------------ state and authority

    /** Codes that are not a State / UT ("Other Territory", "Centre Jurisdiction"). */
    private static final Set<String> NOT_A_STATE = Set.of("97", "99");

    /** The State / UT of the GSTIN's state code; empty for a short GSTIN, an unknown code, or 97 / 99. */
    public static Optional<String> stateForGstin(String gstin) {
        if (gstin == null || gstin.trim().length() < 2 || NOT_A_STATE.contains(gstin.trim().substring(0, 2))) return Optional.empty();
        return GstStateCodes.nameForGstin(gstin);
    }

    private static final Pattern STATE_TAX = Pattern.compile("(?i)\\bstate\\s+tax\\b|\\bSGST\\b(?!\\s+act)");
    private static final Pattern CENTRAL_TAX = Pattern.compile(
            "(?i)\\bcentral\\s+tax\\b|\\bCGST\\b(?![\\s/A-Z]{0,12}\\b(?:act|rules)\\b)|\\bcentral\\s+excise\\b");

    /**
     * "State Tax" or "Central Tax" from the heading (the first {@link #HEAD_CHARS} characters): the one named first;
     * "CGST Act" / "CGST Rules" do not count (a State notice cites them too). Null when the heading names neither.
     */
    public static String authorityKind(String text) {
        if (text == null) return null;
        String head = text.substring(0, Math.min(HEAD_CHARS, text.length()));
        Matcher s = STATE_TAX.matcher(head);
        Matcher c = CENTRAL_TAX.matcher(head);
        int sAt = s.find() ? s.start() : Integer.MAX_VALUE;
        int cAt = c.find() ? c.start() : Integer.MAX_VALUE;
        if (sAt == Integer.MAX_VALUE && cAt == Integer.MAX_VALUE) return null;
        return sAt <= cAt ? AUTHORITY_STATE : AUTHORITY_CENTRAL;
    }

    // ------------------------------------------------------------------ refund

    private static final Pattern RFD_08 = Pattern.compile("(?i)\\bRFD[\\s_\\-]*0?8\\b");
    private static final Pattern REFUND_TYPE = Pattern.compile("(?i)refund|\\brfd[\\s_\\-]*0?8\\b");
    private static final Pattern REFUND_KEYWORD = Pattern.compile("(?i)\\brefund\\s+application\\b|\\brefund\\b|\\bfor\\s+(?=Rs\\b|INR\\b|₹)");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:\\bRs\\b\\.?|\\bINR\\b|₹)\\s*(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)");
    private static final int REFUND_AMOUNT_WINDOW = 150;

    /** True for a refund-stage document: an RFD-08 in the document type or the heading, or a refund document type. */
    public static boolean isRefundDocument(String documentType, String text) {
        if (documentType != null && REFUND_TYPE.matcher(documentType).find()) return true;
        if (text == null) return false;
        return RFD_08.matcher(text.substring(0, Math.min(HEAD_CHARS, text.length()))).find();
    }

    /**
     * The refund amount of a refund notice: the first amount ("Rs.1,78,63,659", "Rs 5,000/-", "INR 12,000") within
     * {@value #REFUND_AMOUNT_WINDOW} characters after "refund" / "Refund Application" / "for Rs." in the first
     * {@link #HEAD_CHARS} characters; null when there is none.
     */
    public static BigDecimal refundAmountFromText(String text) {
        if (text == null || text.isBlank()) return null;
        String head = text.substring(0, Math.min(HEAD_CHARS, text.length()));
        Matcher k = REFUND_KEYWORD.matcher(head);
        while (k.find()) {
            Matcher a = AMOUNT.matcher(head);
            // searched to the end of the head (a window end must not cut "1,78,63,659" to "1,78"); the start is checked
            a.region(k.start(), head.length());
            if (a.find() && a.start() < k.end() + REFUND_AMOUNT_WINDOW) {
                BigDecimal amount = parseAmount(a.group(1));
                if (amount != null && amount.signum() > 0) return amount;
            }
        }
        return null;
    }

    /** "1,78,63,659" / "Rs. 5,000/-" / 5000 as an amount; null when no number. */
    public static BigDecimal parseAmount(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        Matcher m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(value.toString());
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
