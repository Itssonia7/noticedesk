package com.noticedesk.api.service.privacy;

import com.noticedesk.api.service.matching.GstStateCodes;
import com.noticedesk.api.service.matching.NoticeHeaderFacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces everything in a notice text that identifies the client or another party by a placeholder of the same
 * shape, consistently (the same real value gives the same placeholder everywhere), and keeps a reversible
 * {@link AnonymisationMap}. Pure code: no network, no model, no database.
 *
 * <p>Replaced: GSTINs (the state code is kept; the PAN inside is the same placeholder as the standalone PAN), PANs,
 * TANs, CINs, IEC / Aadhaar / bank account numbers (labelled), IFSC codes, phone numbers, e-mail addresses, PIN codes,
 * ARN / DIN / notice reference numbers (shape kept, so the code defect checks still see a DIN-shaped value), the
 * taxpayer's legal / trade name ("NOTICEE COMPANY", the legal-form suffix such as "Private Limited" stays in the text),
 * other parties' names ("SUPPLIER n", a trailing trade word such as "Traders" kept in the placeholder), person names
 * after Shri / Smt / Mr / Ms / Proprietor / Director / Partner / Authorised signatory ("PERSON n"), and the
 * street / building part of addresses ("ADDRESS n"; the city and the State are kept for jurisdiction).
 *
 * <p>Kept: amounts, dates, periods, section numbers, form names, officer designations, the department office, tax
 * rates, HSN codes and invoice numbers (an invoice number is touched only where it contains a party name that is
 * replaced on its own).
 *
 * <p>{@link #deAnonymise} restores every real value (longest placeholder first, case-insensitive, so a model that
 * re-cases "Noticee Company" is still restored). Anonymising an already anonymised text changes nothing.
 */
public final class NoticeAnonymiser {

    /** Anonymised text and its mapping. */
    public record Result(String text, AnonymisationMap map) {
        /** "Privacy: anonymised GSTINs 3, PANs 1, names 4 (...), addresses 2, references 2, other ids 1". */
        public String privacyLine() {
            return "Privacy: anonymised " + map.summaryLine();
        }
    }

    // ---------------------------------------------------------------- placeholders

    public static final String TAXPAYER_PLACEHOLDER = "NOTICEE COMPANY";
    public static final String PARTY_PLACEHOLDER = "SUPPLIER";
    public static final String PERSON_PLACEHOLDER = "PERSON";
    public static final String ADDRESS_PLACEHOLDER = "ADDRESS";

    /** A name that starts with one of this anonymiser's name placeholders is never treated as a real name. */
    private static final Pattern PLACEHOLDER_NAME_PREFIX = Pattern.compile(
            "(?i)^(?:NOTICEE COMPANY|SUPPLIER \\d+|PERSON \\d+|ADDRESS \\d+)(?![A-Za-z0-9])");
    /** A value that is already a placeholder of this anonymiser is left as it is (idempotence). */
    private static final Pattern ALREADY_PLACEHOLDER = Pattern.compile(
            "(?i)^(?:NOTICEE COMPANY(?: \\d+)?|SUPPLIER \\d+(?: [A-Z]+)?|PERSON \\d+|ADDRESS \\d+|person\\d+@example\\.com"
            + "|[A-Z]{5}0\\d{3}[A-Z]|\\d{2}[A-Z]{5}0\\d{3}[A-Z][0-9A-Z]Z[0-9A-Z]|AAAA0\\d{4}A|U00000AA0000AAA\\d{6}"
            + "|AAAA0\\d{6}|90000\\d|9000000\\d{3}|9000 0000 \\d{4}|0{2,}\\d{1,4})$");

    // ---------------------------------------------------------------- identifier patterns

    private static final String NB = "(?<![A-Za-z0-9])";   // not inside a longer alphanumeric token
    private static final String NA = "(?![A-Za-z0-9])";

    private static final Pattern GSTIN = Pattern.compile("(?i)" + NB + "(\\d{2}[A-Z]{5}\\d{4}[A-Z][0-9A-Z]Z[0-9A-Z])" + NA);
    /** A break inside a GSTIN whose 15 characters a PDF text layer split over a line end, spaces or a hyphen. */
    private static final String GSTIN_BREAK = "[ \\t\\r\\n\\-]{0,6}";
    /**
     * A GSTIN split at any point (e.g. the first 8 characters, a line break, the last 7): the same structure as
     * {@link #GSTIN} with an optional break between any two characters, anchored on the state code and the Z check
     * character; upper case only. A contiguous GSTIN matches too and is skipped where this pattern is applied.
     */
    private static final Pattern SPLIT_GSTIN = Pattern.compile(NB + "(\\d" + GSTIN_BREAK + "\\d" + GSTIN_BREAK
            + "[A-Z]" + GSTIN_BREAK + "[A-Z]" + GSTIN_BREAK + "[A-Z]" + GSTIN_BREAK + "[A-Z]" + GSTIN_BREAK + "[A-Z]" + GSTIN_BREAK
            + "\\d" + GSTIN_BREAK + "\\d" + GSTIN_BREAK + "\\d" + GSTIN_BREAK + "\\d" + GSTIN_BREAK
            + "[A-Z]" + GSTIN_BREAK + "[0-9A-Z]" + GSTIN_BREAK + "Z" + GSTIN_BREAK + "[0-9A-Z])" + NA);
    private static final Pattern PAN = Pattern.compile(NB + "([A-Z]{5}\\d{4}[A-Z])" + NA);
    private static final Pattern TAN = Pattern.compile(NB + "([A-Z]{4}\\d{5}[A-Z])" + NA);
    private static final Pattern CIN = Pattern.compile(NB + "([LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6})" + NA);
    private static final Pattern IFSC = Pattern.compile(NB + "([A-Z]{4}0[A-Z0-9]{6})" + NA);
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9A-Za-z])(?:\\+91[\\s\\-]?|0)?([6-9]\\d{9})(?![0-9/])");
    /** "98765 43210" / "98765-43210" (a mobile number written in two halves) and "022-23456789" (STD code + landline). */
    private static final Pattern PHONE_SPLIT = Pattern.compile("(?<![0-9A-Za-z])(?:\\+91[\\s\\-]?|0)?([6-9]\\d{4}[\\s\\-]\\d{5})(?![0-9/])");
    private static final Pattern LANDLINE = Pattern.compile("(?<![0-9A-Za-z+])(0\\d{2,4}[\\s\\-]\\d{6,8})(?![0-9/])");
    private static final Pattern AADHAAR = Pattern.compile("(?<![0-9])(\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4})(?![0-9])");
    private static final Pattern LABELLED_AADHAAR = Pattern.compile("(?i)\\b(?:Aadha?ar|UID)\\s*(?:No\\.?|Number)?\\s*[:\\-–]?\\s*(\\d{12})\\b");
    private static final Pattern LABELLED_IEC = Pattern.compile("(?i)\\bIEC\\s*(?:Code|No\\.?)?\\s*[:\\-–]?\\s*([A-Z0-9]{10})\\b");
    private static final Pattern LABELLED_ACCOUNT = Pattern.compile(
            "(?i)\\b(?:A/c|Account|Acct\\.?|Bank\\s+Account)\\s*(?:No\\.?|Number)?\\s*[:\\-–]?\\s*(\\d{9,18})\\b");
    /** "PIN: 400093", "Pin Code - 400093", "Mumbai - 400093", "Mumbai-400093". */
    private static final Pattern PIN = Pattern.compile(
            "(?i)(?:\\bPIN(?:\\s*code)?\\s*[:\\-–]?\\s*|\\bPincode\\s*[:\\-–]?\\s*|(?<=[A-Za-z)][\\s,]{0,3}[\\-–]\\s{0,2}))([1-9]\\d{5})(?![0-9/])");

    /** Labels that introduce a notice / order / application reference number. */
    private static final Pattern REFERENCE_LABEL = Pattern.compile(
            "(?i)\\b(?:DIN|RFN|ARN|Ref(?:erence)?\\.?\\s*(?:No\\.?|Number)?|Notice\\s+No\\.?|Order\\s+No\\.?|SCN\\s+No\\.?|OIO\\s+No\\.?"
            + "|Case\\s+ID|Temporary\\s+ID|Temp\\.?\\s+ID|Application\\s+Reference\\s+Number|Acknowledg(?:e)?ment\\s+No\\.?"
            + "|File\\s+No\\.?|F\\.\\s*No\\.?|Letter\\s+No\\.?|C\\.\\s*No\\.?|Appeal\\s+No\\.?|Memo\\s+No\\.?)\\s*[:\\-–.]?\\s*");
    /** The reference value itself: letters, digits, '/', '-', '.', '(' ')' with at least one digit, 6+ characters. */
    private static final Pattern REFERENCE_VALUE = Pattern.compile("(?i)(?=[A-Z0-9/\\-().]*\\d)[A-Z0-9][A-Z0-9/\\-().]{5,}");
    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{1,2}[./\\-]\\d{1,2}[./\\-]\\d{2,4}\\.?$|^\\d{4}[./\\-]\\d{1,2}[./\\-]\\d{1,2}$");
    /** Bare ARN (2 letters + 13 digits), portal reference (Z + letter + 12 digits + check), DIN (20 digits). */
    private static final Pattern BARE_REFERENCE = Pattern.compile(
            "(?i)" + NB + "([A-Z]{2}\\d{13}|Z[A-Z]\\d{12}[A-Z0-9]|\\d{20}|\\d{8}[A-Z]{2}\\d{6}[A-Z0-9]{4})" + NA);

    // ---------------------------------------------------------------- name patterns

    private static final Pattern MS = Pattern.compile("(?i)\\bM/s\\b\\.?\\s*");
    private static final Pattern TAXPAYER_NAME_LABEL = Pattern.compile(
            "(?i)\\b(?:legal\\s+name(?:\\s+of\\s+(?:the\\s+)?business)?|trade\\s+name|name\\s+of\\s+(?:the\\s+)?"
            + "(?:RTP|tax\\s*payer|taxable\\s+person|registered\\s+person|noticee|applicant|assessee|claimant|dealer|business|firm|company))"
            + "\\s*[:\\-–]\\s*");
    private static final Pattern PARTY_NAME_LABEL = Pattern.compile(
            "(?i)\\b(?:name\\s+of\\s+(?:the\\s+)?(?:supplier|vendor|recipient|buyer|seller|customer|counter\\s*party|other\\s+party)"
            + "|supplier(?:'s)?\\s+name|vendor(?:'s)?\\s+name|recipient(?:'s)?\\s+name)\\s*[:\\-–]\\s*");
    /** Where a business name ends. */
    private static final Pattern NAME_END = Pattern.compile(
            "(?i)[\\n\\r(\\[\\],;|:]|\\s[-–]\\s|\\bGSTIN\\b|\\bGST\\s*(?:No|Registration|IN)\\b|\\bPAN\\b|\\bhaving\\b|\\bbearing\\b"
            + "|\\bregistered\\b|\\bAddress\\b|\\bwith\\b|\\bdated\\b|\\bfor\\s+the\\b|\\bunder\\b|\\bvide\\b|\\bhas\\b|\\bhave\\b"
            + "|\\bis\\b|\\bwas\\b|\\bwere\\b|\\bare\\b|\\bwho\\b|\\bwhich\\b|\\bthat\\b|\\bfrom\\b|\\bto\\b|\\bat\\b|\\bon\\b"
            + "|\\bin\\b|\\bof\\s+the\\b|\\d{2}[A-Z]{5}\\d{4}|\\bRs\\b|₹|\\bINR\\b|\\bthe\\b");
    private static final int NAME_MAX_WORDS = 8;

    /** Legal-form suffixes stripped (repeatedly) from the end of a business name; they stay in the text. */
    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "(?i)(?:^|[\\s,.]+)(?:\\(?P\\)?\\.?\\s*Ltd\\.?|Pvt\\.?\\s*Ltd\\.?|Private\\s+Limited|Public\\s+Limited|Limited|Ltd\\.?"
            + "|LLP|L\\.?L\\.?P\\.?|OPC|HUF|Proprietorship(?:\\s+concern)?|Proprietor|Prop\\.?|Partnership(?:\\s+Firm)?|Firm|Inc\\.?"
            + "|Corporation|Corpn\\.?|Corp\\.?|Company|Co\\.?|&\\s*Co\\.?|and\\s+Co\\.?|&\\s*Sons|and\\s+Sons|Sons|&\\s*Bros\\.?"
            + "|Brothers|Bros\\.?)\\.?\\s*$");
    /** Generic trade words: kept in an other party's placeholder ("SUPPLIER 1 TRADERS"). */
    private static final Set<String> TRADE_WORDS = Set.of("traders", "trading", "enterprises", "enterprise", "industries",
            "industry", "agencies", "agency", "associates", "services", "solutions", "technologies", "exports", "imports",
            "impex", "textiles", "steel", "steels", "alloys", "metals", "mills", "works", "infra", "infrastructure", "builders",
            "developers", "construction", "constructions", "logistics", "pharma", "pharmaceuticals", "chemicals", "foods",
            "motors", "electricals", "electronics", "engineering", "engineers", "marketing", "distributors", "suppliers",
            "stores", "store", "group", "products", "packaging", "plastics", "polymers", "papers", "garments", "fabrics",
            "jewellers", "hospitality", "hotels", "overseas", "international", "global", "trade", "mart", "agro", "exim");
    /** One-word cores that are too common to be replaced on their own (they are replaced with their suffix attached). */
    private static final Set<String> COMMON_WORDS = Set.of("india", "indian", "national", "global", "universal", "general",
            "modern", "new", "standard", "united", "royal", "super", "star", "shree", "shri", "sri", "om", "jai", "the",
            "central", "state", "city", "metro", "bharat", "hindustan", "prime", "classic", "golden", "silver", "best",
            "quality", "perfect", "supreme", "apex", "alpha", "omega", "delta", "sigma");

    private static final Pattern HONORIFIC = Pattern.compile(
            "(?i)\\b(?:Shri|Smt|Sri|Shrimati|Kumari|Mr|Mrs|Ms|Miss|Dr)\\.?\\s+"
            + "|\\b(?:Proprietor|Proprietress|Partner|Director|Managing\\s+Director|Authori[sz]ed\\s+Signatory|Signatory|Karta|Prop)\\.?\\s*[:\\-–]\\s*");
    private static final Pattern PERSON_WORD = Pattern.compile("^[A-Z][A-Za-z.'\\-]*$");
    private static final Set<String> NOT_IN_A_PERSON_NAME = Set.of("assistant", "deputy", "joint", "additional", "principal",
            "commissioner", "superintendent", "inspector", "officer", "state", "central", "tax", "gst", "cgst", "sgst",
            "proprietor", "director", "partner", "of", "the", "and", "son", "daughter", "wife", "s/o", "d/o", "w/o", "age",
            "aged", "resident", "r/o", "having", "is", "has", "was", "pan", "aadhaar", "mobile", "gstin", "address", "in",
            "on", "at", "for", "to", "by", "with", "said", "noticee", "taxpayer", "authorised", "authorized", "signatory",
            "dated", "vide", "whose", "who", "being", "from", "under", "sir", "madam");

    // ---------------------------------------------------------------- address patterns

    private static final Pattern ADDRESS_LABEL = Pattern.compile(
            "(?i)\\b(?:Address(?:\\s+of\\s+(?:the\\s+)?[A-Za-z ]{1,30}?)?|Add\\.?|Regd\\.?\\s*Office|Registered\\s+Office"
            + "|Principal\\s+Place\\s+of\\s+Business|Place\\s+of\\s+Business|Office\\s+Address|Addl\\.?\\s+Place\\s+of\\s+Business)"
            + "\\s*[:\\-–]\\s*");
    private static final Pattern TO_LINE = Pattern.compile("(?m)^[ \\t]*To[ \\t]*,?[ \\t]*$");
    private static final Pattern SIX_DIGITS = Pattern.compile("(?<![0-9])[1-9]\\d{5}(?![0-9])");
    private static final Set<String> ADDRESS_LINE_STOP = Set.of("gstin", "gst", "pan", "subject", "sub", "ref", "reference",
            "dated", "date", "din", "arn", "notice", "sir", "madam", "to", "from", "cc", "copy", "email", "e-mail", "phone",
            "mobile", "tel", "legal", "trade", "name");

    // ---------------------------------------------------------------- state

    private record Span(int start, int end, String placeholder, String kind, int priority) {}

    private final String text;
    private final AnonymisationMap map = new AnonymisationMap();
    private final List<Span> spans = new ArrayList<>();
    /** kind + "|" + upper-cased real value -> placeholder (consistency across occurrences). */
    private final Map<String, String> placeholders = new HashMap<>();
    private final Map<String, Integer> sequences = new HashMap<>();
    /** Business cores already classified (upper case) -> taxpayer? */
    private final LinkedHashMap<String, Boolean> coreIsTaxpayer = new LinkedHashMap<>();
    private final List<String> stateNames;
    private String taxpayerGstin;
    private final Set<String> otherGstins = new LinkedHashSet<>();
    /** Every GSTIN of the text, whole or split, in text order. */
    private final List<GstinHit> gstinHits = new ArrayList<>();
    /** Split GSTINs: joined real value -> joined placeholder (the mapping gets the joined pair too). */
    private final LinkedHashMap<String, String> splitGstins = new LinkedHashMap<>();

    /** One GSTIN occurrence: [start, end) in the text, its 15 characters joined (upper case), whether it was split. */
    record GstinHit(int start, int end, String joined, boolean split) {}

    private NoticeAnonymiser(String text) {
        this.text = text;
        List<String> states = new ArrayList<>();
        for (String code : List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15",
                "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "29", "30", "31", "32", "33", "34",
                "35", "36", "37", "38")) {
            GstStateCodes.nameForCode(code).ifPresent(states::add);
        }
        states.add("Telangana");
        states.add("Odisha");
        states.add("Orissa");
        states.add("Pondicherry");
        states.add("Bombay");
        states.add("Madras");
        states.add("Calcutta");
        states.add("Bangalore");
        states.add("Bengaluru");
        this.stateNames = states;
    }

    /** Anonymises {@code text}; an empty text gives an empty result. */
    public static Result anonymise(String text) {
        if (text == null || text.isEmpty()) return new Result(text != null ? text : "", new AnonymisationMap());
        return new NoticeAnonymiser(text).run();
    }

    /**
     * Restores every real value: placeholders are matched longest first, as whole tokens, case-insensitively (a model
     * may re-case a name placeholder); text without placeholders is returned unchanged.
     */
    public static String deAnonymise(String text, AnonymisationMap map) {
        if (text == null || text.isEmpty() || map == null || map.isEmpty()) return text;
        List<AnonymisationMap.Entry> entries = new ArrayList<>(map.entries());
        entries.sort(Comparator.comparingInt((AnonymisationMap.Entry e) -> e.placeholder().length()).reversed());
        StringBuilder alternation = new StringBuilder();
        for (AnonymisationMap.Entry e : entries) {
            if (!alternation.isEmpty()) alternation.append('|');
            alternation.append(Pattern.quote(e.placeholder()));
        }
        Pattern p = Pattern.compile("(?i)" + NB + "(?:" + alternation + ")" + NA);
        Matcher m = p.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int last = 0;
        while (m.find()) {
            String real = map.valueOf(m.group());
            out.append(text, last, m.start()).append(real != null ? real : m.group());
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    // ---------------------------------------------------------------- the pass

    private Result run() {
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(text);
        taxpayerGstin = tg.gstin();
        otherGstins.addAll(tg.otherParties());

        collectGstins();
        findIdentifiers();
        findReferences();
        findBusinessNames();
        findPersonNames();
        findAddresses();

        String out = apply();
        return new Result(out, map);
    }

    private String apply() {
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::priority)
                .thenComparing(Comparator.comparingInt(Span::end).reversed()));
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        for (Span s : spans) {
            if (s.start() < last) continue;   // overlaps an accepted span
            String original = text.substring(s.start(), s.end());
            if (s.placeholder().equals(original)) continue;   // already a placeholder: nothing to replace or map
            sb.append(text, last, s.start()).append(s.placeholder());
            map.add(s.kind(), s.placeholder(), original);
            last = s.end();
        }
        sb.append(text, last, text.length());
        // a split GSTIN is mapped in its split form (exact round trip) and in its joined form too, so a model that
        // joins the two halves in its output is still restored
        splitGstins.forEach((joined, placeholder) -> {
            if (!joined.equals(placeholder)) map.add(AnonymisationMap.KIND_GSTIN, placeholder, joined);
        });
        return sb.toString();
    }

    private void add(int start, int end, String placeholder, String kind, int priority) {
        if (start < 0 || end <= start || end > text.length() || placeholder == null) return;
        spans.add(new Span(start, end, placeholder, kind, priority));
    }

    // ---------------------------------------------------------------- identifiers

    /** Every GSTIN of the text, whole ({@link #GSTIN}) or split ({@link #SPLIT_GSTIN}), once each, in text order. */
    private void collectGstins() {
        Matcher m = GSTIN.matcher(text);
        while (m.find()) gstinHits.add(new GstinHit(m.start(1), m.end(1), m.group(1).toUpperCase(Locale.ROOT), false));
        m = SPLIT_GSTIN.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            if (raw.length() == 15) continue;   // contiguous: already collected
            String joined = raw.replaceAll("[\\s\\-]", "");
            if (joined.length() != 15) continue;
            int start = m.start(1);
            int end = m.end(1);
            if (gstinHits.stream().anyMatch(h -> h.start() < end && start < h.end())) continue;
            gstinHits.add(new GstinHit(start, end, joined, true));
        }
        gstinHits.sort(Comparator.comparingInt(GstinHit::start));
    }

    /**
     * The placeholder of a split GSTIN written with the same breaks as {@code raw} ("27AAACB2\n894G1ZN" ->
     * "27AAAAA0\n001A1Z1"), so the table layout and the line count are unchanged.
     */
    static String withBreaksOf(String placeholder, String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int p = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(p < placeholder.length() ? placeholder.charAt(p) : c);
                p++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void findIdentifiers() {
        for (GstinHit h : gstinHits) {
            String raw = text.substring(h.start(), h.end());
            String placeholder = gstinPlaceholder(h.joined());
            if (h.split()) {
                splitGstins.put(h.joined(), placeholder);
                add(h.start(), h.end(), withBreaksOf(placeholder, raw), AnonymisationMap.KIND_GSTIN, 0);
            } else {
                add(h.start(), h.end(), placeholder, AnonymisationMap.KIND_GSTIN, 0);
            }
        }

        Matcher m = PAN.matcher(text);
        while (m.find()) add(m.start(1), m.end(1), panPlaceholder(m.group(1)), AnonymisationMap.KIND_PAN, 1);

        m = CIN.matcher(text);
        while (m.find()) add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_CIN, m.group(1), "U00000AA0000AAA%06d"),
                AnonymisationMap.KIND_CIN, 1);

        m = TAN.matcher(text);
        while (m.find()) add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_TAN, m.group(1), "AAAA0%04dA"), AnonymisationMap.KIND_TAN, 2);

        m = IFSC.matcher(text);
        while (m.find()) {
            String v = m.group(1);
            if (v.matches("[A-Z]{4}0\\d{6}") && isAllCapsWordContext(m.start(1))) {
                add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_IFSC, v, "AAAA0%06d"), AnonymisationMap.KIND_IFSC, 2);
            } else if (!v.matches("[A-Z]{4}0\\d{6}")) {
                add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_IFSC, v, "AAAA0%06d"), AnonymisationMap.KIND_IFSC, 2);
            }
        }

        m = EMAIL.matcher(text);
        while (m.find()) add(m.start(), m.end(), sequenced(AnonymisationMap.KIND_EMAIL, m.group(), "person%d@example.com"),
                AnonymisationMap.KIND_EMAIL, 1);

        m = LABELLED_AADHAAR.matcher(text);
        while (m.find()) add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_AADHAAR, m.group(1), "90000000%04d"),
                AnonymisationMap.KIND_AADHAAR, 1);
        m = AADHAAR.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replaceAll("\\D", "");
            String sep = m.group(1).substring(4, 5);
            String ph = sequenced(AnonymisationMap.KIND_AADHAAR, digits, "90000000%04d");
            add(m.start(1), m.end(1), ph.substring(0, 4) + sep + ph.substring(4, 8) + sep + ph.substring(8), AnonymisationMap.KIND_AADHAAR, 1);
        }

        m = LABELLED_IEC.matcher(text);
        while (m.find()) {
            String v = m.group(1);
            if (!v.matches("\\d{2}[A-Z]{5}.*")) {
                add(m.start(1), m.end(1), shapePlaceholder(AnonymisationMap.KIND_IEC, v), AnonymisationMap.KIND_IEC, 1);
            }
        }

        m = LABELLED_ACCOUNT.matcher(text);
        while (m.find()) add(m.start(1), m.end(1), shapePlaceholder(AnonymisationMap.KIND_ACCOUNT, m.group(1)), AnonymisationMap.KIND_ACCOUNT, 1);

        m = PHONE.matcher(text);
        while (m.find()) {
            if (currencyBefore(m.start(1)) || text.startsWith("/-", m.end(1))) continue;
            add(m.start(1), m.end(1), sequenced(AnonymisationMap.KIND_PHONE, m.group(1), "9000000%03d"), AnonymisationMap.KIND_PHONE, 2);
        }
        m = PHONE_SPLIT.matcher(text);
        while (m.find()) {
            if (currencyBefore(m.start(1))) continue;
            String digits = m.group(1).replaceAll("\\D", "");
            String sep = m.group(1).substring(5, 6);
            String ph = sequenced(AnonymisationMap.KIND_PHONE, digits, "9000000%03d");
            add(m.start(1), m.end(1), ph.substring(0, 5) + sep + ph.substring(5), AnonymisationMap.KIND_PHONE, 2);
        }
        m = LANDLINE.matcher(text);
        while (m.find()) {
            if (currencyBefore(m.start(1))) continue;
            add(m.start(1), m.end(1), shapePlaceholder(AnonymisationMap.KIND_PHONE, m.group(1)), AnonymisationMap.KIND_PHONE, 3);
        }

        m = PIN.matcher(text);
        while (m.find()) {
            if (currencyBefore(m.start(1))) continue;
            add(m.start(1), m.end(1), pinPlaceholder(m.group(1)), AnonymisationMap.KIND_PIN, 3);
        }
    }

    private boolean isAllCapsWordContext(int at) {
        // an IFSC with six digits ("SBIN0001234") is accepted only when a bank-ish label is near: IFSC / IFS / Bank
        int from = Math.max(0, at - 40);
        String before = text.substring(from, at).toLowerCase(Locale.ROOT);
        return before.contains("ifsc") || before.contains("ifs code") || before.contains("bank") || before.contains("branch");
    }

    private boolean currencyBefore(int at) {
        int from = Math.max(0, at - 8);
        String before = text.substring(from, at).toLowerCase(Locale.ROOT);
        return before.matches(".*(?:rs\\.?|inr|₹|rupees)\\s*$");
    }

    private String gstinPlaceholder(String gstin) {
        String g = gstin.toUpperCase(Locale.ROOT);
        String key = AnonymisationMap.KIND_GSTIN + "|" + g;
        String existing = placeholders.get(key);
        if (existing != null) return existing;
        if (ALREADY_PLACEHOLDER.matcher(g).matches()) {
            placeholders.put(key, gstin);
            return gstin;
        }
        String pan = panPlaceholder(g.substring(2, 12));
        int seq = nextSequence(AnonymisationMap.KIND_GSTIN);
        String check = seq <= 9 ? String.valueOf(seq) : String.valueOf((char) ('A' + (seq - 10) % 26));
        String candidate = g.substring(0, 2) + pan + g.charAt(12) + "Z" + check;
        while (placeholders.containsValue(candidate)) {
            seq = nextSequence(AnonymisationMap.KIND_GSTIN);
            check = seq <= 9 ? String.valueOf(seq) : String.valueOf((char) ('A' + (seq - 10) % 26));
            candidate = g.substring(0, 2) + pan + g.charAt(12) + "Z" + check;
        }
        placeholders.put(key, candidate);
        return candidate;
    }

    private String panPlaceholder(String pan) {
        String p = pan.toUpperCase(Locale.ROOT);
        return sequenced(AnonymisationMap.KIND_PAN, p, "AAAAA%04dA");
    }

    private String pinPlaceholder(String pin) {
        return sequenced(AnonymisationMap.KIND_PIN, pin, "9000%02d");
    }

    /** The placeholder of {@code value} for {@code kind}: the same value always gets the same one; {@code format} takes the sequence number. */
    private String sequenced(String kind, String value, String format) {
        String key = kind + "|" + value.toUpperCase(Locale.ROOT);
        String existing = placeholders.get(key);
        if (existing != null) return existing;
        if (ALREADY_PLACEHOLDER.matcher(value).matches()) {
            placeholders.put(key, value);
            return value;
        }
        String candidate;
        do {
            candidate = String.format(Locale.ROOT, format, nextSequence(kind));
        } while (placeholders.containsValue(candidate));
        placeholders.put(key, candidate);
        return candidate;
    }

    private int nextSequence(String kind) {
        int n = sequences.getOrDefault(kind, 0) + 1;
        sequences.put(kind, n);
        return n;
    }

    /**
     * Same-shape placeholder: letters and punctuation kept, every run of digits replaced by a run of the same length
     * carrying the sequence number (right-aligned, zero-padded). A four-digit year (19xx / 20xx) delimited by
     * separators and the two-digit year after it ("2023-24") are kept in a structured reference such as
     * "SCN/MH/2023-24/606".
     */
    private String shapePlaceholder(String kind, String value) {
        String key = kind + "|" + value.toUpperCase(Locale.ROOT);
        String existing = placeholders.get(key);
        if (existing != null) return existing;
        String candidate;
        int guard = 0;
        do {
            candidate = reshape(value, nextSequence(kind));
        } while (placeholders.containsValue(candidate) && ++guard < 200);
        if (placeholders.containsValue(candidate)) candidate = candidate + "X";
        placeholders.put(key, candidate);
        return candidate;
    }

    static String reshape(String value, int seq) {
        boolean structured = value.contains("/") || value.contains("-");
        StringBuilder out = new StringBuilder(value.length());
        Matcher m = Pattern.compile("\\d+").matcher(value);
        int last = 0;
        boolean previousWasYear = false;
        while (m.find()) {
            out.append(value, last, m.start());
            String run = m.group();
            boolean year = structured && run.length() == 4 && (run.startsWith("19") || run.startsWith("20"));
            boolean fyTail = structured && previousWasYear && run.length() == 2 && m.start() > 0 && value.charAt(m.start() - 1) == '-';
            if (year || fyTail) {
                out.append(run);
            } else {
                String digits = String.valueOf(seq);
                if (digits.length() >= run.length()) out.append(digits.substring(digits.length() - run.length()));
                else out.append("0".repeat(run.length() - digits.length())).append(digits);
            }
            previousWasYear = year;
            last = m.end();
        }
        out.append(value, last, value.length());
        return out.toString();
    }

    // ---------------------------------------------------------------- references

    private void findReferences() {
        Matcher m = REFERENCE_LABEL.matcher(text);
        while (m.find()) {
            int at = m.end();
            Matcher v = REFERENCE_VALUE.matcher(text);
            v.region(at, Math.min(text.length(), at + 60));
            if (!v.lookingAt()) continue;
            String value = v.group();
            // a trailing '.' or ')' closing the sentence is not part of the reference
            while (value.endsWith(".") || (value.endsWith(")") && !value.contains("("))) value = value.substring(0, value.length() - 1);
            if (value.length() < 6 || DATE_LIKE.matcher(value).matches()) continue;
            if (GSTIN.matcher(value).find() || PAN.matcher(value).matches()) continue;   // handled as identifiers
            if (value.matches("(?i)[A-Z]+(?:-\\d{1,2}[A-Z]?)?") || value.matches("(?i)(?:FORM|GST|DRC|ASMT|RFD|APL)[A-Z0-9/\\-]*")) continue;
            add(v.start(), v.start() + value.length(), shapePlaceholder(AnonymisationMap.KIND_REFERENCE, value), AnonymisationMap.KIND_REFERENCE, 2);
        }
        m = BARE_REFERENCE.matcher(text);
        while (m.find()) {
            add(m.start(1), m.end(1), shapePlaceholder(AnonymisationMap.KIND_REFERENCE, m.group(1)), AnonymisationMap.KIND_REFERENCE, 3);
        }
    }

    // ---------------------------------------------------------------- business names

    private record BusinessName(int start, int end, String core, String suffix, String full) {}

    private void findBusinessNames() {
        // 1. the taxpayer's legal name next to the taxpayer GSTIN (NoticeHeaderFacts)
        String legal = NoticeHeaderFacts.taxpayerLegalName(text, taxpayerGstin);
        if (legal != null) {
            BusinessName b = split(legal);
            if (b != null) coreIsTaxpayer.putIfAbsent(b.core().toUpperCase(Locale.ROOT), true);
        }
        // 2. labelled names
        Matcher m = TAXPAYER_NAME_LABEL.matcher(text);
        while (m.find()) {
            BusinessName b = nameAt(m.end());
            if (b != null) classify(b, true);
        }
        m = PARTY_NAME_LABEL.matcher(text);
        while (m.find()) {
            BusinessName b = nameAt(m.end());
            if (b != null) classify(b, false);
        }
        // 3. "M/s ..." names: the taxpayer's when the nearest GSTIN is the taxpayer's (or none is near and no other
        //    taxpayer name is known in the head), else another party's
        m = MS.matcher(text);
        while (m.find()) {
            BusinessName b = nameAt(m.end());
            if (b == null) continue;
            String upper = b.core().toUpperCase(Locale.ROOT);
            Boolean known = coreIsTaxpayer.get(upper);
            if (known != null) continue;
            String nearest = nearestGstin(b.start(), b.end());
            boolean taxpayer;
            if (nearest != null) taxpayer = nearest.equals(taxpayerGstin);
            else taxpayer = b.start() < NoticeHeaderFacts.HEAD_CHARS && !coreIsTaxpayer.containsValue(true);
            coreIsTaxpayer.put(upper, taxpayer);
        }
        // 4. the addressee line after "To,"
        m = TO_LINE.matcher(text);
        if (m.find()) {
            String[] lines = text.substring(m.end()).split("\n", 6);
            for (int i = 0; i < Math.min(lines.length, 3); i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) continue;
                if (line.matches("(?i)^(?:The\\s+)?(?:Proprietor|Partner|Director|Principal\\s+Officer|Managing\\s+Director|Authori[sz]ed\\s+Signatory|Karta)\\s*,?$")) continue;
                if (line.matches("(?i)^M/s\\.?$")) continue;
                String candidate = line.replaceFirst("(?i)^M/s\\b\\.?\\s*", "").replaceFirst("[,.;]+$", "");
                if (SIX_DIGITS.matcher(candidate).find() || GSTIN.matcher(candidate).find()) break;
                BusinessName b = split(candidate);
                if (b != null && looksLikeName(b.core())) {
                    coreIsTaxpayer.putIfAbsent(b.core().toUpperCase(Locale.ROOT), true);
                }
                break;
            }
        }
        // 5. names in the cell next to another party's GSTIN (table rows)
        Matcher g = GSTIN.matcher(text);
        while (g.find()) {
            String value = g.group(1).toUpperCase(Locale.ROOT);
            if (value.equals(taxpayerGstin)) continue;
            for (String cell : neighbourCells(g.start(1), g.end(1))) {
                BusinessName b = split(cell.replaceFirst("(?i)^M/s\\b\\.?\\s*", ""));
                if (b != null && looksLikeName(b.core())) coreIsTaxpayer.putIfAbsent(b.core().toUpperCase(Locale.ROOT), false);
            }
        }
        // placeholders numbered in the order the names first appear in the text (the legal name read by the header
        // facts stays "NOTICEE COMPANY" without a number)
        List<String> ordered = new ArrayList<>(coreIsTaxpayer.keySet());
        Map<String, Integer> firstAt = new HashMap<>();
        for (String core : ordered) firstAt.put(core, firstOccurrence(core));
        ordered.sort(Comparator.comparingInt((String c) -> firstAt.get(c)));
        String legalCore = coreIsTaxpayer.isEmpty() ? null : coreIsTaxpayer.keySet().iterator().next();
        int taxpayerN = 0;
        Map<String, String> base = new HashMap<>();
        if (legalCore != null && coreIsTaxpayer.get(legalCore) && !ALREADY_PLACEHOLDER.matcher(legalCore).matches()) {
            taxpayerN = 1;
            base.put(legalCore, TAXPAYER_PLACEHOLDER);
        }
        for (String core : ordered) {
            if (base.containsKey(core) || ALREADY_PLACEHOLDER.matcher(core).matches()) continue;
            if (coreIsTaxpayer.get(core)) {
                taxpayerN++;
                base.put(core, taxpayerN == 1 ? TAXPAYER_PLACEHOLDER : TAXPAYER_PLACEHOLDER + " " + taxpayerN);
            } else {
                base.put(core, partyPlaceholder(core));
            }
        }
        // replace every occurrence of every core (longest cores first, so "Zeta Alloys" wins over "Zeta")
        List<String> cores = new ArrayList<>(base.keySet());
        cores.sort(Comparator.comparingInt(String::length).reversed());
        for (String core : cores) {
            String kind = coreIsTaxpayer.get(core) ? AnonymisationMap.KIND_TAXPAYER_NAME : AnonymisationMap.KIND_PARTY_NAME;
            replaceCore(core, base.get(core), kind, 4);
        }
    }

    /** "SUPPLIER n", with the core's trailing trade word kept ("SUPPLIER n TRADERS"); one per distinct core. */
    private String partyPlaceholder(String core) {
        String upper = core.toUpperCase(Locale.ROOT);
        String key = AnonymisationMap.KIND_PARTY_NAME + "|" + upper;
        String existing = placeholders.get(key);
        if (existing != null) return existing;
        String last = upper.contains(" ") ? upper.substring(upper.lastIndexOf(' ') + 1) : upper;
        boolean trade = TRADE_WORDS.contains(last.toLowerCase(Locale.ROOT)) && upper.contains(" ");
        String ph = PARTY_PLACEHOLDER + " " + nextSequence(AnonymisationMap.KIND_PARTY_NAME) + (trade ? " " + last : "");
        placeholders.put(key, ph);
        return ph;
    }

    /** Index of the first occurrence of {@code core} (case-insensitive, any whitespace), or the text length. */
    private int firstOccurrence(String core) {
        StringBuilder re = new StringBuilder("(?i)");
        String[] words = core.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) re.append("\\s+");
            re.append(Pattern.quote(words[i]));
        }
        Matcher m = Pattern.compile(re.toString()).matcher(text);
        return m.find() ? m.start() : text.length();
    }

    /** Adds a span for every occurrence of {@code core} (case-insensitive, whole words; spaces match any whitespace). */
    private void replaceCore(String core, String placeholder, String kind, int priority) {
        String[] words = core.trim().split("\\s+");
        boolean bareAllowed = words.length >= 2 || (core.length() >= 5 && !COMMON_WORDS.contains(core.toLowerCase(Locale.ROOT)));
        StringBuilder re = new StringBuilder("(?i)" + NB + "(");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) re.append("\\s+");
            re.append(Pattern.quote(words[i]));
        }
        re.append(")");
        if (!bareAllowed) {
            // a short / common one-word core is replaced only together with its legal-form suffix
            re.append("(?=[\\s,.]+(?:\\(?P\\)?\\.?\\s*Ltd|Pvt|Private|Limited|Ltd|LLP|Proprietor|Prop\\b|Co\\b|& Co|and Co|Inc|Corporation|Corp|Company|Enterprises|Traders|Industries|Agencies))");
        }
        re.append(NA);
        Matcher m = Pattern.compile(re.toString()).matcher(text);
        while (m.find()) {
            String found = m.group(1);
            boolean upper = found.equals(found.toUpperCase(Locale.ROOT));
            add(m.start(1), m.end(1), upper ? placeholder : titleCase(placeholder), kind, priority);
        }
    }

    static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetterOrDigit(c);
        }
        return sb.toString();
    }

    private void classify(BusinessName b, boolean taxpayer) {
        String upper = b.core().toUpperCase(Locale.ROOT);
        coreIsTaxpayer.putIfAbsent(upper, taxpayer);
    }

    /** The business name starting at {@code at} (up to {@link #NAME_END}); null when it does not read as a name. */
    private BusinessName nameAt(int at) {
        if (at >= text.length()) return null;
        String rest = text.substring(at, Math.min(text.length(), at + 200));
        Matcher e = NAME_END.matcher(rest);
        String raw = e.find() ? rest.substring(0, e.start()) : rest;
        raw = raw.replaceFirst("(?i)^M/s\\b\\.?\\s*", "").replaceAll("\\s+", " ").replaceAll("^[\\s:.\\-–]+|[\\s:.\\-–]+$", "").trim();
        if (raw.length() < 3 || raw.length() > 120) return null;
        BusinessName b = split(raw);
        if (b == null || !looksLikeName(b.core())) return null;
        return new BusinessName(at, at + raw.length(), b.core(), b.suffix(), raw);
    }

    /** Splits a name into its core and its legal-form suffix; null when nothing is left. */
    static BusinessName split(String name) {
        if (name == null) return null;
        String n = name.replaceAll("\\s+", " ").trim();
        if (PLACEHOLDER_NAME_PREFIX.matcher(n).find()) return null;
        String suffix = "";
        for (int i = 0; i < 4; i++) {
            Matcher s = LEGAL_SUFFIX.matcher(n);
            if (!s.find()) break;
            if (s.start() == 0) break;   // the whole name is a suffix word: keep it
            suffix = n.substring(s.start()).trim() + (suffix.isEmpty() ? "" : " " + suffix);
            n = n.substring(0, s.start()).trim();
        }
        n = n.replaceAll("[\\s,.]+$", "");
        if (n.isEmpty()) return null;
        return new BusinessName(0, 0, n, suffix, name);
    }

    static boolean looksLikeName(String core) {
        if (core == null) return false;
        String[] words = core.trim().split("\\s+");
        if (words.length == 0 || words.length > NAME_MAX_WORDS) return false;
        if (!Character.isLetter(core.charAt(0)) || !Character.isUpperCase(core.charAt(0))) return false;
        if (PLACEHOLDER_NAME_PREFIX.matcher(core).find()) return false;
        int letters = 0;
        int capitalised = 0;
        for (String w : words) {
            String lw = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z/]", "");
            if (NOT_IN_A_PERSON_NAME.contains(lw) && !lw.equals("and") && !lw.equals("of") && !lw.equals("the")) return false;
            if (w.chars().anyMatch(Character::isLetter)) letters++;
            if (Character.isUpperCase(w.charAt(0)) || w.equals(w.toUpperCase(Locale.ROOT))) capitalised++;
        }
        if (core.chars().filter(Character::isDigit).count() * 2 > core.length()) return false;
        return letters * 2 >= words.length && capitalised * 2 >= words.length;
    }

    /** The GSTIN (whole or split) nearest to [start, end) within 160 characters (upper case, joined), or null. */
    private String nearestGstin(int start, int end) {
        int from = Math.max(0, start - 160);
        int to = Math.min(text.length(), end + 160);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (GstinHit h : gstinHits) {
            if (h.end() <= from || h.start() >= to) continue;
            int dist = h.start() >= end ? h.start() - end : h.end() <= start ? start - h.end() : 0;
            if (dist < bestDist) {
                bestDist = dist;
                best = h.joined();
            }
        }
        return best;
    }

    /** The table cells right before and after the GSTIN at [start, end) on its line (cells split on '|', tabs, 2+ spaces). */
    private List<String> neighbourCells(int start, int end) {
        int ls = text.lastIndexOf('\n', start) + 1;
        int le = text.indexOf('\n', end);
        if (le < 0) le = text.length();
        String line = text.substring(ls, le);
        String[] cells = line.split("\\s*\\|\\s*|\\t+|\\s{2,}");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i].toUpperCase(Locale.ROOT).contains(text.substring(start, end).toUpperCase(Locale.ROOT))) {
                if (i > 0) out.add(cells[i - 1].trim());
                if (i + 1 < cells.length) out.add(cells[i + 1].trim());
            }
        }
        List<String> names = new ArrayList<>();
        for (String c : out) {
            String cell = c.replaceFirst("^\\(?[a-z0-9]{1,3}\\)\\s*", "").replaceFirst("^\\d{1,3}[.)]\\s*", "").trim();
            if (cell.length() < 3 || cell.length() > 80) continue;
            if (cell.matches("(?i).*\\b(?:gstin|pan|name|supplier|vendor|total|sr|no|date|invoice|amount|taxable|value|igst|cgst|sgst|tax|rate|qty|hsn|period|month)\\b.*")) continue;
            if (cell.chars().filter(Character::isDigit).count() * 3 > cell.length()) continue;
            names.add(cell);
        }
        return names;
    }

    // ---------------------------------------------------------------- person names

    private void findPersonNames() {
        Matcher m = HONORIFIC.matcher(text);
        Pattern leadingHonorific = Pattern.compile("(?i)^(?:Shri|Smt|Sri|Shrimati|Kumari|Mr|Mrs|Ms|Miss|Dr)\\.?\\s+");
        while (m.find()) {
            int at = m.end();
            // "Authorised Signatory - Ms. Priya Nair": the honorific after the designation stays outside the placeholder
            Matcher lead = leadingHonorific.matcher(text.substring(at, Math.min(text.length(), at + 12)));
            if (lead.find()) at += lead.end();
            String rest = text.substring(at, Math.min(text.length(), at + 80));
            String[] tokens = rest.split("(?<=\\s)|(?=[,;:()\\n])", -1);
            StringBuilder name = new StringBuilder();
            int consumed = 0;
            int words = 0;
            for (String tok : tokens) {
                String w = tok.trim();
                if (w.isEmpty()) {
                    if (words == 0 || tok.isBlank() && !name.isEmpty() && tok.contains("\n")) {
                        if (tok.contains("\n")) break;
                        consumed += tok.length();
                        continue;
                    }
                    break;
                }
                String bare = w.replaceAll("[.,]+$", "");
                if (words == 1 && bare.matches("\\d{1,3}") && name.toString().trim().equalsIgnoreCase("Person")) {
                    name.append(tok);   // "Person 1": an existing placeholder
                    consumed += tok.length();
                    words++;
                    continue;
                }
                if (words >= 4 || !PERSON_WORD.matcher(bare).matches() && !bare.equals(bare.toUpperCase(Locale.ROOT))
                        || NOT_IN_A_PERSON_NAME.contains(bare.toLowerCase(Locale.ROOT)) || bare.length() < 2 && words > 0 && !bare.endsWith(".")) {
                    break;
                }
                if (!Character.isLetter(bare.charAt(0))) break;
                name.append(tok);
                consumed += tok.length();
                words++;
            }
            String found = name.toString().replaceAll("[\\s,.]+$", "").trim();
            if (found.isEmpty() || words == 0) continue;
            String lastWord = found.substring(found.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (TRADE_WORDS.contains(lastWord) || LEGAL_SUFFIX.matcher(" " + found).find()) {
                // "Shri Ram Traders": a business, not a person
                BusinessName b = split(found);
                if (b != null && looksLikeName(b.core()) && !coreIsTaxpayer.containsKey(b.core().toUpperCase(Locale.ROOT))) {
                    coreIsTaxpayer.put(b.core().toUpperCase(Locale.ROOT), false);
                    replaceCore(b.core(), partyPlaceholder(b.core()), AnonymisationMap.KIND_PARTY_NAME, 4);
                }
                continue;
            }
            if (ALREADY_PLACEHOLDER.matcher(found).matches()) continue;
            String key = AnonymisationMap.KIND_PERSON_NAME + "|" + found.toUpperCase(Locale.ROOT);
            String ph = placeholders.get(key);
            if (ph == null) {
                ph = PERSON_PLACEHOLDER + " " + nextSequence(AnonymisationMap.KIND_PERSON_NAME);
                placeholders.put(key, ph);
                // every other occurrence of the same person (with or without the honorific)
                replaceCore(found, ph, AnonymisationMap.KIND_PERSON_NAME, 5);
            }
            boolean upper = found.equals(found.toUpperCase(Locale.ROOT));
            add(at, at + found.length(), upper ? ph : titleCase(ph), AnonymisationMap.KIND_PERSON_NAME, 5);
        }
    }

    // ---------------------------------------------------------------- addresses

    private record AddressCandidate(int start, int end, String replaced) {}

    private final List<AddressCandidate> addresses = new ArrayList<>();

    private void findAddresses() {
        Matcher m = ADDRESS_LABEL.matcher(text);
        while (m.find()) addressSpan(m.end(), 250);
        findAddresseeBlocks();
        // numbered in text order, one placeholder per distinct street / building text
        addresses.sort(Comparator.comparingInt(AddressCandidate::start));
        for (AddressCandidate c : addresses) {
            String key = AnonymisationMap.KIND_ADDRESS + "|" + c.replaced().toUpperCase(Locale.ROOT);
            String ph = placeholders.get(key);
            if (ph == null) {
                ph = ADDRESS_PLACEHOLDER + " " + nextSequence(AnonymisationMap.KIND_ADDRESS);
                placeholders.put(key, ph);
            }
            add(c.start(), c.end(), ph, AnonymisationMap.KIND_ADDRESS, 6);
        }
    }

    private void findAddresseeBlocks() {

        Matcher t = TO_LINE.matcher(text);
        while (t.find()) {
            int at = t.end();
            int lineNo = 0;
            boolean nameSeen = false;
            while (at < text.length() && lineNo < 6) {
                int le = text.indexOf('\n', at);
                if (le < 0) le = text.length();
                String line = text.substring(at, le);
                String s = line.strip();
                lineNo++;
                if (s.isEmpty()) {
                    if (nameSeen) break;
                    at = le + 1;
                    continue;
                }
                String first = s.split("[\\s:.,]+", 2)[0].toLowerCase(Locale.ROOT);
                if (ADDRESS_LINE_STOP.contains(first) || GSTIN.matcher(s).find() || PAN.matcher(s).find() || s.contains(":")) break;
                if (!nameSeen) {
                    nameSeen = true;   // the addressee (handled by the name rules) or a designation line
                    if (s.matches("(?i)^(?:The\\s+)?(?:Proprietor|Partner|Director|Principal\\s+Officer|Managing\\s+Director|Authori[sz]ed\\s+Signatory|Karta)\\s*,?$")) {
                        nameSeen = false;
                    }
                    at = le + 1;
                    continue;
                }
                int start = at + (line.length() - line.stripLeading().length());
                addressSpan(start, s.length());
                if (SIX_DIGITS.matcher(s).find()) break;   // the PIN line ends the block
                at = le + 1;
            }
        }
    }

    /**
     * Replaces the street / building part of the address starting at {@code from}: up to the PIN code or the end of the
     * line (at most {@code maxLen} characters); the city (the segment before the State or the PIN) and the State are kept.
     */
    private void addressSpan(int from, int maxLen) {
        if (from >= text.length()) return;
        int le = text.indexOf('\n', from);
        if (le < 0) le = text.length();
        int end = Math.min(le, from + maxLen);
        String seg = text.substring(from, end);
        Matcher pin = SIX_DIGITS.matcher(seg);
        int cut = seg.length();
        if (pin.find()) cut = pin.start();
        String addr = seg.substring(0, cut);
        if (pin.find(0) && !currencyBefore(from + pin.start())) {
            add(from + pin.start(), from + pin.end(), pinPlaceholder(pin.group()), AnonymisationMap.KIND_PIN, 3);
        }
        // strip the PIN label and the separator before the PIN ("Mumbai - " / "Mumbai, " / "Gujarat, PIN ")
        String trimmed = addr.replaceAll("(?i)[\\s,\\-–]*\\b(?:PIN(?:\\s*code)?|Pincode)\\s*[:\\-–]?\\s*$", "").replaceAll("[\\s,\\-–]+$", "");
        if (trimmed.isBlank()) return;
        if (GSTIN.matcher(trimmed).find()) return;
        // the city and State segments at the end are kept (a known city or State name only; a locality is not)
        String[] parts = trimmed.split("\\s*[,\\-–]\\s*");
        int keepFrom = parts.length;   // index of the first kept part
        for (int i = parts.length - 1; i >= 0 && i >= parts.length - 3; i--) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                keepFrom = i;
                continue;
            }
            if (isState(p)) {
                keepFrom = i;
            } else {
                break;
            }
        }
        if (keepFrom == 0) return;   // nothing but city / State
        int replacedLen = 0;
        if (keepFrom < parts.length) {
            // position of the first kept part in trimmed
            int pos = 0;
            for (int i = 0; i < keepFrom; i++) {
                pos = trimmed.indexOf(parts[i], pos) + parts[i].length();
            }
            replacedLen = pos;
        } else {
            replacedLen = trimmed.length();
        }
        String replaced = trimmed.substring(0, replacedLen).replaceAll("[\\s,\\-–]+$", "");
        if (replaced.isBlank() || ALREADY_PLACEHOLDER.matcher(replaced).matches()) return;
        if (!replaced.chars().anyMatch(Character::isLetterOrDigit)) return;
        addresses.add(new AddressCandidate(from, from + replaced.length(), replaced));
    }

    private boolean isState(String part) {
        String p = part.replaceAll("[^A-Za-z ]", "").trim();
        if (p.isEmpty()) return false;
        for (String s : stateNames) if (s.equalsIgnoreCase(p)) return true;
        return p.matches("(?i)(?:New\\s+)?Delhi|Mumbai|Pune|Thane|Nagpur|Nashik|Ahmedabad|Surat|Vadodara|Rajkot|Chennai|Coimbatore|Madurai"
                + "|Hyderabad|Secunderabad|Kolkata|Howrah|Bengaluru|Bangalore|Mysuru|Mysore|Kochi|Cochin|Ernakulam|Thiruvananthapuram|Trivandrum"
                + "|Jaipur|Jodhpur|Udaipur|Lucknow|Kanpur|Noida|Ghaziabad|Agra|Varanasi|Gurugram|Gurgaon|Faridabad|Chandigarh|Ludhiana"
                + "|Amritsar|Jalandhar|Bhopal|Indore|Jabalpur|Raipur|Patna|Ranchi|Bhubaneswar|Cuttack|Guwahati|Dehradun|Shimla|Panaji|Goa"
                + "|Visakhapatnam|Vijayawada|Guntur|Tirupati|Nellore|Vapi|Valsad|Bharuch|Ankleshwar|Gandhinagar|Jamnagar|Bhavnagar|Aurangabad"
                + "|Solapur|Kolhapur|Sangli|Satara|Ratnagiri|Palghar|Navi Mumbai|Kalyan|Dombivli|Bhiwandi|Vasai|Virar|Ulhasnagar|Ambernath"
                + "|Salem|Erode|Tiruppur|Trichy|Tiruchirappalli|Vellore|Hosur|Mangaluru|Mangalore|Hubballi|Hubli|Belagavi|Belgaum|Kozhikode|Calicut|Thrissur");
    }
}
