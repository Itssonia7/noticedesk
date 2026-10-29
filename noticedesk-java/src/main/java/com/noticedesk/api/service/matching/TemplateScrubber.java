package com.noticedesk.api.service.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the notice-specific values of an AI-written section (client name, GSTIN, notice reference,
 * dates, amounts, tax period, identifying facts) by template placeholders so the text can be stored as a
 * reusable reply template. Every value is matched in the forms the writers use ("Rs 4,82,310",
 * "4,82,310/-", "482310", "4.82 lakh", "15-10-2023", "15th October, 2023", "FY 2022-23",
 * "April 2022 to March 2023", "M/s ABC Pvt. Ltd." / "ABC Private Limited" ...).
 *
 * <p>{@link #scrub} never returns text that still carries the legal name's distinctive words or an
 * amount's digits: it throws instead, so a promotion fails loudly rather than storing a real value in the
 * global catalogue.
 */
public final class TemplateScrubber {

    /** Placeholder names (as used by {@link TemplateFillService#buildContextMap}). */
    public static final String CLIENT_LEGAL_NAME = "client.legal_name";
    public static final String CLIENT_GSTIN = "client.gstin";
    public static final String CLIENT_TRADE_NAME = "client.trade_name";
    public static final String NOTICE_REFERENCE = "notice.reference";
    public static final String NOTICE_ISSUE_DATE = "notice.issue_date";
    public static final String NOTICE_TOTAL_DEMAND = "notice.total_demand_amount";
    public static final String ISSUE_AMOUNT_TAX = "issue.amount_tax";
    public static final String ISSUE_PERIOD = "issue.period";

    private static final Set<String> NAME_NOISE = Set.of("m", "s", "ms", "m/s", "pvt", "private", "ltd", "limited",
            "llp", "co", "company", "corp", "corporation", "inc", "the", "and", "&", "of", "p");
    /** A company suffix after the distinctive words; must end at a word boundary ("co" never eats "could"). */
    private static final String COMPANY_SUFFIX =
            "\\s*(?:\\(?(?:p|pvt|private)\\.?\\)?\\s*(?:ltd|limited)\\.?|llp|ltd\\.?|limited|inc\\.?|co\\.?|company|corp(?:oration)?\\.?)(?![A-Za-z0-9])";
    /** Facts whose string value identifies the notice / client (replaced); legal content keys are kept. */
    private static final Pattern IDENTIFYING_FACT_KEY = Pattern.compile(
            "(?i)(name|gstin|pan|invoice|number|_no$|^no$|ref|din|arn|address|vendor|supplier|party|order|email|phone|mobile)");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

    private final List<Rule> rules = new ArrayList<>();
    private final List<LeakCheck> leakChecks = new ArrayList<>();
    private final Set<String> placeholders = new LinkedHashSet<>();

    /** {@code priority}: 0 = identity literals (GSTIN, references), 1 = names, 2 = dates / amounts / periods. */
    private record Rule(String placeholder, Pattern pattern, int priority) {}

    private record LeakCheck(String placeholder, String description, Pattern pattern) {}

    /** The values of one notice / issue; null or blank entries are ignored. */
    public static final class Values {
        private String legalName;
        private String tradeName;
        private String gstin;
        private final List<String> references = new ArrayList<>();
        private LocalDate issueDate;
        private BigDecimal amountTax;
        private BigDecimal totalDemand;
        private String period;
        private final Map<String, Object> otherFacts = new LinkedHashMap<>();

        public Values legalName(String v) { this.legalName = v; return this; }
        public Values tradeName(String v) { this.tradeName = v; return this; }
        public Values gstin(String v) { this.gstin = v; return this; }
        public Values reference(String v) { if (v != null && !v.isBlank()) references.add(v.trim()); return this; }
        public Values issueDate(LocalDate v) { this.issueDate = v; return this; }
        public Values amountTax(BigDecimal v) { this.amountTax = v; return this; }
        public Values totalDemand(BigDecimal v) { this.totalDemand = v; return this; }
        public Values period(String v) { this.period = v; return this; }
        /** Other issue facts (key -> value); numeric and identifying string values get {@code issue.<key>} placeholders. */
        public Values fact(String key, Object v) { if (key != null && v != null) otherFacts.put(key, v); return this; }
    }

    public TemplateScrubber(Values v) {
        if (v.legalName != null && !v.legalName.isBlank()) {
            addName(CLIENT_LEGAL_NAME, v.legalName);
        }
        if (v.tradeName != null && !v.tradeName.isBlank() && (v.legalName == null || !v.tradeName.equalsIgnoreCase(v.legalName))) {
            addName(CLIENT_TRADE_NAME, v.tradeName);
        }
        if (v.gstin != null && !v.gstin.isBlank()) {
            rules.add(new Rule(CLIENT_GSTIN, Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(v.gstin.trim()) + "(?![A-Z0-9])"), 0));
            leakChecks.add(new LeakCheck(CLIENT_GSTIN, "GSTIN", Pattern.compile("(?i)" + Pattern.quote(v.gstin.trim()))));
        }
        for (String ref : v.references) {
            if (ref.length() >= 4) rules.add(new Rule(NOTICE_REFERENCE, literal(ref), 0));
        }
        if (v.issueDate != null) {
            rules.add(new Rule(NOTICE_ISSUE_DATE, datePattern(v.issueDate), 2));
        }
        if (v.amountTax != null && v.amountTax.signum() > 0) {
            addAmount(ISSUE_AMOUNT_TAX, v.amountTax);
        }
        if (v.totalDemand != null && v.totalDemand.signum() > 0 && (v.amountTax == null || v.totalDemand.compareTo(v.amountTax) != 0)) {
            addAmount(NOTICE_TOTAL_DEMAND, v.totalDemand);
        }
        if (v.period != null && !v.period.isBlank()) {
            rules.add(new Rule(ISSUE_PERIOD, periodPattern(v.period), 2));
        }
        for (Map.Entry<String, Object> e : v.otherFacts.entrySet()) {
            String key = e.getKey().trim();
            Object val = e.getValue();
            if (key.isEmpty() || val == null) continue;
            String text = val.toString().trim();
            if (text.isEmpty()) continue;
            String placeholder = "issue." + key;
            if (val instanceof Number || NUMERIC.matcher(text).matches()) {
                try {
                    BigDecimal amount = new BigDecimal(text);
                    if (amount.signum() > 0 && amount.compareTo(BigDecimal.valueOf(999)) > 0) addAmount(placeholder, amount);
                } catch (NumberFormatException ignored) { /* not an amount */ }
            } else if (IDENTIFYING_FACT_KEY.matcher(key).find() && text.length() >= 4) {
                rules.add(new Rule(placeholder, literal(text), 0));
            }
        }
        // identity literals first (a reference may contain digit runs that look like a date or amount), then
        // names, then the rest; within a priority the longest pattern first so "M/s ABC Pvt Ltd" wins over "ABC"
        rules.sort(Comparator.comparingInt(Rule::priority)
                .thenComparing((a, b) -> Integer.compare(b.pattern().pattern().length(), a.pattern().pattern().length())));
    }

    /** Placeholders this scrubber inserted so far (template {@code variables}). */
    public Set<String> placeholdersUsed() {
        return Set.copyOf(placeholders);
    }

    /**
     * Returns {@code text} with every known value replaced by its {@code {{placeholder}}}.
     *
     * @throws IllegalStateException when the legal name's distinctive words, the GSTIN or an amount's digits
     *                               are still present afterwards (the text must not become a template)
     */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement("{{" + r.placeholder() + "}}"));
                placeholders.add(r.placeholder());
            }
        }
        out = TemplateFillService.collapseDuplicateCurrency(out.replaceAll("(?i)(?:\\bRs\\.?|\\bINR|₹)\\s*(?=\\{\\{)", ""));
        for (LeakCheck c : leakChecks) {
            if (c.pattern().matcher(out).find()) {
                throw new IllegalStateException("promotion refused: the " + c.description()
                        + " is still present in the section text after placeholder replacement (" + c.placeholder() + ")");
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- pattern builders

    private void addName(String placeholder, String name) {
        List<String> tokens = significantTokens(name);
        if (tokens.isEmpty()) {
            rules.add(new Rule(placeholder, literal(name), 1));
            return;
        }
        // "M/s ABC Steel Pvt. Ltd." == "ABC Steel Private Limited" == "ABC Steel": the distinctive words with
        // any separators between them, with optional M/s prefix and company suffix
        StringBuilder core = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) core.append("[\\s.,&/-]*");
            core.append(Pattern.quote(tokens.get(i)));
        }
        String pattern = "(?i)(?:\\bM/s\\.?\\s*)?(?<![A-Za-z0-9])" + core + "(?![A-Za-z0-9])"
                + "(?:" + COMPANY_SUFFIX + ")*";
        rules.add(new Rule(placeholder, Pattern.compile(pattern), 1));
        rules.add(new Rule(placeholder, literal(name), 1));
        // leak check: the distinctive words in sequence (one word: only when it is not a common word)
        if (tokens.size() >= 2 || tokens.get(0).length() >= 5) {
            leakChecks.add(new LeakCheck(placeholder, "client name", Pattern.compile("(?i)(?<![A-Za-z0-9])" + core + "(?![A-Za-z0-9])")));
        }
    }

    static List<String> significantTokens(String name) {
        List<String> out = new ArrayList<>();
        for (String t : name.toLowerCase(Locale.ROOT).replace("m/s", " ").replaceAll("[^a-z0-9]+", " ").trim().split("\\s+")) {
            if (!t.isEmpty() && !NAME_NOISE.contains(t)) out.add(t);
        }
        return out;
    }

    private void addAmount(String placeholder, BigDecimal amount) {
        rules.add(new Rule(placeholder, amountPattern(amount), 2));
        String digits = amount.setScale(0, RoundingMode.DOWN).abs().toPlainString();
        if (digits.length() >= 4) {
            // any grouping: compare on the text with separators removed
            leakChecks.add(new LeakCheck(placeholder, "amount " + digits, Pattern.compile("(?<!\\d)" + groupedDigits(digits) + "(?!\\d)")));
        }
    }

    /** "4,82,310", "482,310", "482310", "4,82,310.00", "4,82,310/-", "Rs. 4,82,310", "4.82 lakh", "4.8231 lakhs". */
    static Pattern amountPattern(BigDecimal amount) {
        String digits = amount.setScale(0, RoundingMode.DOWN).abs().toPlainString();
        String currency = "(?:(?:Rs\\.?|INR|₹|Rupees)\\s*)?";
        String plain = currency + "(?<!\\d)" + groupedDigits(digits) + "(?:\\.\\d{1,2})?(?!\\d)(?:\\s*/-)?(?:\\s*only)?";
        List<String> alternatives = new ArrayList<>();
        alternatives.add(plain);
        if (amount.compareTo(BigDecimal.valueOf(100_000)) >= 0) {
            alternatives.add(currency + unitForm(amount, 100_000, "lakhs?|lacs?|L"));
        }
        if (amount.compareTo(BigDecimal.valueOf(10_000_000)) >= 0) {
            alternatives.add(currency + unitForm(amount, 10_000_000, "crores?|cr\\.?"));
        }
        return Pattern.compile("(?i)(?:" + String.join("|", alternatives) + ")");
    }

    private static String groupedDigits(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append("[,\\s]?");
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private static String unitForm(BigDecimal amount, long unit, String unitWords) {
        BigDecimal exact = amount.divide(BigDecimal.valueOf(unit), 4, RoundingMode.HALF_UP).stripTrailingZeros();
        BigDecimal two = amount.divide(BigDecimal.valueOf(unit), 2, RoundingMode.HALF_UP).stripTrailingZeros();
        Set<String> forms = new LinkedHashSet<>();
        forms.add(Pattern.quote(exact.toPlainString()));
        forms.add(Pattern.quote(two.toPlainString()));
        if (two.scale() <= 0) forms.add(Pattern.quote(two.toPlainString() + ".00"));
        return "(?<![\\d.])(?:" + String.join("|", forms) + ")\\s*(?:" + unitWords + ")\\b";
    }

    /** "15-10-2023", "15.10.2023", "15/10/2023", "2023-10-15", "15 October 2023", "15th October, 2023", "October 15, 2023". */
    static Pattern datePattern(LocalDate d) {
        int day = d.getDayOfMonth();
        String dayRe = (day < 10 ? "0?" + day : String.valueOf(day)) + "(?:st|nd|rd|th)?";
        String monthNum = d.getMonthValue() < 10 ? "0?" + d.getMonthValue() : String.valueOf(d.getMonthValue());
        String monthName = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String monthShort = d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String monthRe = "(?:" + monthNum + "|" + Pattern.quote(monthName) + "|" + Pattern.quote(monthShort) + "\\.?)";
        String year = String.valueOf(d.getYear());
        String sep = "[-./\\s,]+";   // at least one separator: a digit run inside a reference is never a date
        String dmy = "(?<!\\d)" + dayRe + sep + monthRe + sep + year + "(?!\\d)";
        String mdy = "(?<![A-Za-z0-9])" + monthRe + sep + dayRe + sep + year + "(?!\\d)";
        String iso = "(?<!\\d)" + year + "[-./]" + String.format("%02d", d.getMonthValue()) + "[-./]" + String.format("%02d", day) + "(?!\\d)";
        return Pattern.compile("(?i)(?:" + dmy + "|" + mdy + "|" + iso + ")");
    }

    /**
     * The raw period text plus, when it can be normalised, its usual forms: "2022-23" / "FY 2022-23" /
     * "F.Y. 2022-2023" for a financial year, "April 2022 to March 2023", "01.04.2022 to 31.03.2023",
     * "July 2019" / "07/2019" for a single month.
     */
    static Pattern periodPattern(String period) {
        List<String> alternatives = new ArrayList<>();
        alternatives.add(literal(period).pattern().replace("(?i)", ""));
        Optional<PeriodNormalizer.Period> parsed = PeriodNormalizer.parse(period);
        if (parsed.isPresent()) {
            LocalDate start = parsed.get().start();
            LocalDate end = parsed.get().end();
            String fy = "(?:F\\.?\\s*Y\\.?\\s*|financial\\s+year\\s+)?";
            if (start.getDayOfMonth() == 1 && start.getMonthValue() == 4 && end.getMonthValue() == 3 && end.getDayOfMonth() == 31
                    && end.getYear() == start.getYear() + 1) {
                String yy = String.format("%02d", end.getYear() % 100);
                alternatives.add(fy + "(?<!\\d)" + start.getYear() + "\\s*[-–/]\\s*(?:" + end.getYear() + "|" + yy + ")(?!\\d)");
            }
            String range = "\\s*(?:to|till|through|upto|up to|-|–)\\s*";
            alternatives.add(monthYear(start) + range + monthYear(end));
            alternatives.add(dayMonthYear(start) + range + dayMonthYear(end));
            if (start.getYear() == end.getYear() && start.getMonth() == end.getMonth()) {
                alternatives.add(monthYear(start));
                alternatives.add("(?<!\\d)" + String.format("%02d", start.getMonthValue()) + "[-/]" + start.getYear() + "(?!\\d)");
            }
        }
        return Pattern.compile("(?i)(?:" + String.join("|", alternatives) + ")");
    }

    private static String monthYear(LocalDate d) {
        String name = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String shortName = d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return "(?:" + Pattern.quote(name) + "|" + Pattern.quote(shortName) + "\\.?)[\\s,]*" + d.getYear();
    }

    private static String dayMonthYear(LocalDate d) {
        return "(?<!\\d)0?" + d.getDayOfMonth() + "[-./\\s]+(?:0?" + d.getMonthValue() + "|"
                + Pattern.quote(d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)) + "|"
                + Pattern.quote(d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) + "\\.?)[-./\\s,]+" + d.getYear() + "(?!\\d)";
    }

    /** The literal text, case-insensitive, with any whitespace run matching any whitespace run. */
    static Pattern literal(String text) {
        String[] parts = text.trim().split("\\s+");
        StringBuilder sb = new StringBuilder("(?i)(?<![A-Za-z0-9])");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("\\s+");
            sb.append(Pattern.quote(parts[i]));
        }
        sb.append("(?![A-Za-z0-9])");
        return Pattern.compile(sb.toString());
    }
}
