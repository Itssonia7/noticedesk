package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
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
     * Sum of the amounts of the demand issues (defect and consequential issues left out); null when there is no demand
     * issue or one of them has no readable amount (an incomplete sum is not compared).
     */
    public static BigDecimal sumOfIssueAmounts(List<MatchedIssue> issues) {
        if (issues == null) return null;
        BigDecimal sum = null;
        for (MatchedIssue i : issues) {
            if (i == null || i.isDefect() || i.isConsequential()) continue;
            Object amt = i.facts() != null ? i.facts().get("amount") : null;
            BigDecimal v = NoticeHeaderFacts.parseAmount(amt);
            if (v == null || v.signum() <= 0) return null;
            sum = sum == null ? v : sum.add(v);
        }
        return sum;
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
        BigDecimal issueSum = sumOfIssueAmounts(issues);
        if (stated != null && issueSum != null && !differ(stated, issueSum)) return stated;
        if (stated == null) return issueSum;
        return null;
    }
}
