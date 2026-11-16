package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The total demand of a notice, checked by code. The matcher's {@code total_demand_amount} is a model output and was
 * once off by a factor of ten (Rs 4,49,46,680 for a notice demanding Rs 34,71,680 + Rs 10,23,000); the draft printed it
 * unchecked. This class reads the notice's own demand clauses ("show cause ... as to why (i) ITC of Rs X should not be
 * demanded ...") and sums the issue amounts, so the stated total can be corrected and checked.
 */
public final class DemandTotals {

    /** Relative tolerance between two totals (1%). */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private DemandTotals() {}

    private static final Pattern WHY_ANCHOR = Pattern.compile("(?is)show\\s+cause\\b.{0,400}?\\bwhy\\b");
    /** The next numbered paragraph ("9. The noticee may ...") ends the demand clauses. */
    private static final Pattern NEXT_PARA = Pattern.compile("(?m)^\\s*\\d{1,2}\\.\\s+[A-Z]");
    private static final Pattern CLAUSE_MARK = Pattern.compile("(?i)(?:^|\\s)\\(\\s*(?:[ivx]{1,5}|[a-h])\\s*\\)\\s*");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:\\bRs\\b\\.?|\\bINR\\b|₹)\\s*(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern NOT_TAX = Pattern.compile("(?i)\\binterest\\b|\\bpenalt|\\blate\\s+fee|\\bfine\\b|\\bredemption\\b");
    private static final Pattern DEMAND_VERB = Pattern.compile("(?i)demand|recover|\\bpaid\\b|\\bpay\\b|payable|revers|disallow");
    private static final int MAX_CLAUSE_CHARS = 4000;

    /**
     * Sum of the tax amounts in the notice's demand clauses (the lettered / roman clauses after "show cause ... why"),
     * one amount per clause (its first), interest / penalty / late-fee clauses left out. Null when the text has no
     * such clause with an amount.
     */
    public static BigDecimal fromDemandClauses(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher a = WHY_ANCHOR.matcher(text);
        BigDecimal best = null;
        while (a.find()) {
            String tail = text.substring(a.end(), Math.min(text.length(), a.end() + MAX_CLAUSE_CHARS));
            Matcher next = NEXT_PARA.matcher(tail);
            if (next.find()) tail = tail.substring(0, next.start());
            List<String> clauses = splitClauses(tail);
            if (clauses.size() < 2 && !CLAUSE_MARK.matcher(tail).find()) continue;   // no lettered clauses here
            BigDecimal sum = null;
            for (String c : clauses) {
                Matcher m = AMOUNT.matcher(c);
                if (!m.find()) continue;
                if (NOT_TAX.matcher(c.substring(0, m.start())).find()) continue;
                if (!DEMAND_VERB.matcher(c).find()) continue;
                BigDecimal v = NoticeHeaderFacts.parseAmount(m.group(1));
                if (v == null || v.signum() <= 0) continue;
                sum = sum == null ? v : sum.add(v);
            }
            if (sum != null) best = best == null || sum.compareTo(best) > 0 ? sum : best;
        }
        return best;
    }

    private static List<String> splitClauses(String tail) {
        List<String> out = new ArrayList<>();
        Matcher m = CLAUSE_MARK.matcher(tail);
        int start = -1;
        while (m.find()) {
            if (start >= 0) out.add(tail.substring(start, m.start()));
            start = m.end();
        }
        if (start >= 0) out.add(tail.substring(start));
        return out;
    }

    /**
     * Sum of the tax / ITC amounts of the demand issues (defect and consequential issues left out, and so are issues
     * whose amount is a late fee, interest or penalty: see {@link #components}); null when there is no such issue or
     * one of them has no readable amount (an incomplete sum is not compared). The legal section alone classifies here.
     */
    public static BigDecimal sumOfIssueAmounts(List<MatchedIssue> issues) {
        return sumOfIssueAmounts(issues, null);
    }

    /**
     * Sum of the tax / ITC amounts of the demand issues, classified with the notice text as well ({@link #components}):
     * an issue whose amount is the notice's late fee + interest (Objection 5 of the synthetic Section 73 notice) is not
     * a tax amount and is not compared with the tax demand clause. Null when there is no tax issue or one lacks an amount.
     */
    public static BigDecimal sumOfIssueAmounts(List<MatchedIssue> issues, String noticeText) {
        if (issues == null) return null;
        BigDecimal sum = null;
        for (MatchedIssue i : issues) {
            if (i == null || i.isDefect() || i.isConsequential()) continue;
            Map<AmountKind, BigDecimal> c = components(i, noticeText);
            if (c.isEmpty()) {
                if (!isTaxBySection(i)) continue;   // a late fee / interest / penalty issue without an amount
                return null;
            }
            BigDecimal v = c.get(AmountKind.TAX);
            if (v == null) continue;
            sum = sum == null ? v : sum.add(v);
        }
        return sum;
    }

    // ------------------------------------------------------------------ tax vs late fee / interest / penalty

    /** What an amount is: tax / ITC, late fee (Section 47), interest (Section 50) or penalty; MIXED = non-tax, not split. */
    public enum AmountKind { TAX, LATE_FEE, INTEREST, PENALTY, MIXED;

        /** Plain label for the reply's summary table and the check messages. */
        public String label() {
            return switch (this) {
                case TAX -> "Tax";
                case LATE_FEE -> "Late fee";
                case INTEREST -> "Interest";
                case PENALTY -> "Penalty";
                case MIXED -> "Late fee / interest / penalty";
            };
        }
    }

    /** A late fee, interest or penalty figure stated in the notice's demand clauses, with the section that follows it. */
    public record StatedFigure(AmountKind kind, BigDecimal amount, String section) {}

    private static final Pattern LATE_FEE_WORDS = Pattern.compile("(?i)\\blate\\s*[- ]?fees?\\b");
    private static final Pattern INTEREST_WORDS = Pattern.compile("(?i)\\binterest\\b");
    private static final Pattern PENALTY_WORDS = Pattern.compile("(?i)\\bpenalt(?:y|ies)\\b");
    /** "Section 47", "Sections 47 and 50(1)", "u/s 125", "73(9)": the section numbers of a legal-section fact. */
    private static final Pattern SECTION_NO = Pattern.compile("(?i)(?<![\\w(/.])(\\d{1,3}(?!\\d)[A-Z]?)\\s*(\\(\\s*\\d+[A-Za-z]?\\s*\\))?");
    private static final Pattern NOT_A_SECTION = Pattern.compile(
            "(?i)\\b(?:rules?|notification|circular|form|no\\.?)\\s+[\\d()A-Za-z/.,\\s-]*?(?=\\bsection|\\bread\\b|\\band\\b|;|$)"
            + "|\\bof\\s+the\\s+(?:IGST|UTGST|SGST|[A-Z]+GST)\\s+Act[^;]*");
    private static final Pattern SECTION_AFTER = Pattern.compile("(?i)^\\s*(?:/-)?\\s*(?:under|u/s\\.?)\\s+(Section\\s+\\d{1,3}[A-Z]?(?:\\s*\\(\\s*\\d+\\s*\\))?)");

    /** The kind a section number stands for: 47 late fee, 50 interest, 122 / 123 / 125 / 127 and 73(9) / 74(9) penalty. */
    static AmountKind sectionKind(String no, String sub) {
        String n = no.toUpperCase(java.util.Locale.ROOT);
        String s = sub == null ? "" : sub.replaceAll("\s", "");
        return switch (n) {
            case "47" -> AmountKind.LATE_FEE;
            case "50" -> AmountKind.INTEREST;
            case "122", "123", "125", "127" -> AmountKind.PENALTY;
            case "73", "74" -> "(9)".equals(s) ? AmountKind.PENALTY : AmountKind.TAX;
            case "74A" -> "(5)".equals(s) ? AmountKind.PENALTY : AmountKind.TAX;
            default -> AmountKind.TAX;
        };
    }

    /** The kinds of the issue's legal-section fact (empty when it names no section). */
    static java.util.EnumSet<AmountKind> sectionKinds(MatchedIssue i) {
        java.util.EnumSet<AmountKind> out = java.util.EnumSet.noneOf(AmountKind.class);
        Object legal = i.facts() != null ? i.facts().get("legal_section") : null;
        if (legal == null || legal.toString().isBlank()) return out;
        String t = NOT_A_SECTION.matcher(legal.toString()).replaceAll(" ");
        Matcher m = SECTION_NO.matcher(t);
        while (m.find()) out.add(sectionKind(m.group(1), m.group(2)));
        return out;
    }

    private static boolean isTaxBySection(MatchedIssue i) {
        java.util.EnumSet<AmountKind> k = sectionKinds(i);
        return k.isEmpty() || k.contains(AmountKind.TAX);
    }

    /**
     * The issue's amount split by kind: tax / ITC, late fee, interest, penalty. Read from the issue's notice paragraph
     * first: an amount equal to a figure the paragraph labels "late fee" / "interest" / "penalty", or to the sum of those
     * figures (Rs 59,060 = late fee Rs 17,800 + interest Rs 41,260), is split into them; else from the legal section
     * (Section 47 late fee, 50 interest, 122 / 125 / 73(9) penalty; several non-tax sections give {@code MIXED}); else
     * tax. Empty when the issue has no readable amount.
     */
    public static Map<AmountKind, BigDecimal> components(MatchedIssue i, String noticeText) {
        Map<AmountKind, BigDecimal> out = new java.util.EnumMap<>(AmountKind.class);
        if (i == null) return out;
        Object amt = i.facts() != null ? i.facts().get("amount") : null;
        BigDecimal v = NoticeHeaderFacts.parseAmount(amt);
        if (v == null || v.signum() <= 0) return out;
        List<Labelled> labelled = new ArrayList<>();
        for (String para : issueParagraphTexts(i, noticeText)) labelled.addAll(labelledAmounts(para));
        boolean equalsTax = labelled.stream().anyMatch(l -> l.kind == AmountKind.TAX && same(l.amount, v));
        if (!equalsTax) {
            for (Labelled l : labelled) {
                if (l.kind != AmountKind.TAX && same(l.amount, v)) {
                    out.put(l.kind, v);
                    return out;
                }
            }
            BigDecimal nonTax = null;
            Map<AmountKind, BigDecimal> split = new java.util.EnumMap<>(AmountKind.class);
            for (Labelled l : labelled) {
                if (l.kind == AmountKind.TAX) continue;
                nonTax = nonTax == null ? l.amount : nonTax.add(l.amount);
                split.merge(l.kind, l.amount, BigDecimal::add);
            }
            if (nonTax != null && same(nonTax, v)) return split;
        }
        java.util.EnumSet<AmountKind> kinds = sectionKinds(i);
        if (!kinds.isEmpty() && !kinds.contains(AmountKind.TAX)) {
            out.put(kinds.size() == 1 ? kinds.iterator().next() : AmountKind.MIXED, v);
            return out;
        }
        out.put(AmountKind.TAX, v);
        return out;
    }

    private record Labelled(AmountKind kind, BigDecimal amount) {}

    /** Each rupee amount of a paragraph with the kind the words just before it name ("Late fee under Section 47 of Rs X"). */
    static List<Labelled> labelledAmounts(String para) {
        List<Labelled> out = new ArrayList<>();
        Matcher m = AMOUNT.matcher(para);
        int prev = 0;
        while (m.find()) {
            String before = para.substring(Math.max(prev, m.start() - 60), m.start());
            int cut = Math.max(before.lastIndexOf(". "), before.lastIndexOf("; "));
            if (cut >= 0) before = before.substring(cut + 1);
            AmountKind k = lastLabel(before);
            BigDecimal a = NoticeHeaderFacts.parseAmount(m.group(1));
            if (a != null && a.signum() > 0) out.add(new Labelled(k, a));
            prev = m.end();
        }
        return out;
    }

    /** The kind named last in {@code s} (late fee / interest / penalty), else TAX. */
    private static AmountKind lastLabel(String s) {
        int best = -1;
        AmountKind k = AmountKind.TAX;
        for (var e : Map.of(AmountKind.LATE_FEE, LATE_FEE_WORDS, AmountKind.INTEREST, INTEREST_WORDS,
                AmountKind.PENALTY, PENALTY_WORDS).entrySet()) {
            Matcher m = e.getValue().matcher(s);
            while (m.find()) {
                if (m.start() > best) {
                    best = m.start();
                    k = e.getKey();
                }
            }
        }
        return k;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    /** The text of the notice paragraphs the issue cites ("6" -> the paragraph that starts "6. "), whole paragraphs only. */
    static List<String> issueParagraphTexts(MatchedIssue i, String noticeText) {
        List<String> out = new ArrayList<>();
        if (noticeText == null || i.noticeParas() == null) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String ref : i.noticeParas()) {
            Matcher n = Pattern.compile("^\\s*(\\d{1,3})(?!\\d)").matcher(ref != null ? ref : "");
            if (n.find()) seen.add(n.group(1));
        }
        for (String no : seen) {
            String p = paragraph(noticeText, no);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Paragraph {@code no} of the notice: from the line "no. " to the next numbered paragraph line (or a blank line). */
    public static String paragraph(String noticeText, String no) {
        if (noticeText == null) return null;
        Matcher start = Pattern.compile("(?m)^\\s*" + Pattern.quote(no) + "\\.\\s+").matcher(noticeText);
        if (!start.find()) return null;
        String tail = noticeText.substring(start.end());
        Matcher next = Pattern.compile("(?m)^\\s*(?:\\d{1,3}\\.\\s+[A-Z(]|\\s*$)").matcher(tail);
        int end = tail.length();
        while (next.find()) {
            if (next.start() > 0) {
                end = next.start();
                break;
            }
        }
        return tail.substring(0, end).replaceAll("\\s+", " ").trim();
    }

    /**
     * The late fee, interest and penalty figures of the notice's demand clauses ("(c) late fee of Rs. 17,800/- should not
     * be recovered under Section 47; (d) penalty of Rs. 1,10,957/- under Section 73(9) and Rs. 1,50,000/- under Section
     * 125 ..."), each amount with the section after it. A clause without an amount ("interest should not be recovered")
     * gives nothing. Empty when the text has no demand clauses.
     */
    public static List<StatedFigure> nonTaxDemandClauses(String text) {
        List<StatedFigure> best = new ArrayList<>();
        if (text == null || text.isBlank()) return best;
        Matcher a = WHY_ANCHOR.matcher(text);
        while (a.find()) {
            String tail = text.substring(a.end(), Math.min(text.length(), a.end() + MAX_CLAUSE_CHARS));
            Matcher next = NEXT_PARA.matcher(tail);
            if (next.find()) tail = tail.substring(0, next.start());
            List<String> clauses = splitClauses(tail);
            if (clauses.size() < 2) continue;
            List<StatedFigure> found = new ArrayList<>();
            for (String c : clauses) {
                Matcher m = AMOUNT.matcher(c);
                if (!m.find()) continue;
                AmountKind k = lastLabel(c.substring(0, m.start()));
                if (k == AmountKind.TAX) continue;
                m.reset();
                while (m.find()) {
                    BigDecimal v = NoticeHeaderFacts.parseAmount(m.group(1));
                    if (v == null || v.signum() <= 0) continue;
                    Matcher sec = SECTION_AFTER.matcher(c.substring(m.end()));
                    String section = sec.find() ? sec.group(1).replaceAll("\\s+", " ") : null;
                    if (section == null) {
                        Matcher anySec = Pattern.compile("(?i)\\bunder\\s+(Section\\s+\\d{1,3}[A-Z]?(?:\\s*\\(\\s*\\d+\\s*\\))?)").matcher(c);
                        if (anySec.find()) section = anySec.group(1).replaceAll("\\s+", " ");
                    }
                    found.add(new StatedFigure(k, v, section));
                }
            }
            if (found.size() > best.size()) best = found;
        }
        return best;
    }

    /**
     * Each non-tax kind the notice states (late fee, interest, penalty) against the same kind in the issue amounts:
     * a message per kind more than 1% apart. Only compared when the notice states the kind with an amount, at least one
     * issue carries that kind, and no issue amount is an unsplit mix of non-tax kinds.
     */
    public static List<String> nonTaxMismatches(List<MatchedIssue> issues, String noticeText) {
        List<String> out = new ArrayList<>();
        List<StatedFigure> stated = nonTaxDemandClauses(noticeText);
        if (stated.isEmpty() || issues == null) return out;
        Map<AmountKind, BigDecimal> fromIssues = new java.util.EnumMap<>(AmountKind.class);
        for (MatchedIssue i : issues) {
            if (i == null || i.isDefect()) continue;
            Map<AmountKind, BigDecimal> c = components(i, noticeText);
            if (c.containsKey(AmountKind.MIXED)) return out;
            c.forEach((k, v) -> { if (k != AmountKind.TAX) fromIssues.merge(k, v, BigDecimal::add); });
        }
        for (AmountKind k : List.of(AmountKind.LATE_FEE, AmountKind.INTEREST, AmountKind.PENALTY)) {
            BigDecimal s = stated.stream().filter(f -> f.kind() == k).map(StatedFigure::amount).reduce(null,
                    (x, y) -> x == null ? y : x.add(y));
            BigDecimal f = fromIssues.get(k);
            if (s != null && f != null && differ(s, f)) {
                out.add(k.label() + " " + fmt(f) + " in the issue amounts differs from the notice's " + k.label().toLowerCase(java.util.Locale.ROOT)
                        + " clause " + fmt(s));
            }
        }
        return out;
    }

    private static String fmt(BigDecimal v) {
        return TemplateFillService.formatIndianCurrency(v);
    }

    /** True when both are known and differ by more than {@link #TOLERANCE} of the larger. */
    public static boolean differ(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal max = a.abs().max(b.abs());
        if (max.signum() == 0) return false;
        return a.subtract(b).abs().divide(max, 6, RoundingMode.HALF_UP).compareTo(TOLERANCE) > 0;
    }

    public static BigDecimal of(Double d) {
        return d != null ? BigDecimal.valueOf(d) : null;
    }

    /**
     * The total tax demand the draft may state as verified: the notice's demand-clause total when the text has one,
     * else the stated total when the issue amounts confirm it (within 1%), else the issue sum when nothing is stated;
     * null when it cannot be verified.
     */
    public static BigDecimal verifiedTotal(MatchedNoticeInfo notice, List<MatchedIssue> issues, String noticeText) {
        BigDecimal stated = notice != null ? of(notice.totalDemandAmount()) : null;
        BigDecimal clauses = fromDemandClauses(noticeText);
        if (clauses != null) return clauses;
        BigDecimal issueSum = sumOfIssueAmounts(issues, noticeText);
        if (stated != null && issueSum != null && !differ(stated, issueSum)) return stated;
        if (stated == null) return issueSum;
        return null;
    }
}
