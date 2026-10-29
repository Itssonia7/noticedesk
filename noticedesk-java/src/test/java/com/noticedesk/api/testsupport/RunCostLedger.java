package com.noticedesk.api.testsupport;

import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.ApiUsageLogService.UsageCall;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects every billed model call reported by {@link ApiUsageLogService#logCall}, including failed
 * attempts and retries, prints one line per call and sums the cost. Prints no prompt or output text.
 */
public class RunCostLedger {

    private final List<UsageCall> calls = new ArrayList<>();
    private final PrintStream out;
    private int unpricedCalls = 0;

    public RunCostLedger(PrintStream out) {
        this.out = out;
    }

    public static RunCostLedger attach(ApiUsageLogService service, PrintStream out) {
        RunCostLedger ledger = new RunCostLedger(out);
        service.addCallListener(ledger::record);
        return ledger;
    }

    public synchronized void record(UsageCall c) {
        calls.add(c);
        if (c.costInr() == null) unpricedCalls++;
        out.printf(Locale.ROOT,
                "  [call] step=%s attempt=%d stop_reason=%s%s in=%d cache_read=%d cache_write=%d out=%d INR=%s%n",
                c.step(), c.attempt(), c.stopReason(),
                c.failureReason() != null ? " FAILED(" + c.failureReason() + ")" : "",
                c.inputTokens(), c.cacheReadTokens(), c.cacheWriteTokens(), c.outputTokens(),
                c.costInr() != null ? c.costInr().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString() : "UNPRICED");
    }

    public synchronized BigDecimal totalInr() {
        return sumInr(calls);
    }

    /** Cost of the calls recorded since {@code fromIndex} (use {@link #size()} as a checkpoint). */
    public synchronized BigDecimal totalInrSince(int fromIndex) {
        return sumInr(calls.subList(Math.min(fromIndex, calls.size()), calls.size()));
    }

    public synchronized BigDecimal totalInrSince(int fromIndex, String step) {
        BigDecimal t = BigDecimal.ZERO;
        for (UsageCall c : calls.subList(Math.min(fromIndex, calls.size()), calls.size())) {
            if (step.equals(c.step()) && c.costInr() != null) t = t.add(c.costInr());
        }
        return t;
    }

    public synchronized int size() {
        return calls.size();
    }

    public synchronized int failedCalls() {
        return (int) calls.stream().filter(c -> c.failureReason() != null).count();
    }

    public synchronized int unpricedCalls() {
        return unpricedCalls;
    }

    public synchronized List<UsageCall> calls() {
        return List.copyOf(calls);
    }

    private static BigDecimal sumInr(List<UsageCall> list) {
        BigDecimal t = BigDecimal.ZERO;
        for (UsageCall c : list) {
            if (c.costInr() != null) t = t.add(c.costInr());
        }
        return t;
    }

    /** Percentage as text, or "N/A" when there were no successful calls or nothing to measure. */
    public static String pct(int numerator, int denominator, int successfulCalls) {
        if (successfulCalls <= 0 || denominator <= 0) return "N/A";
        return String.format(Locale.ROOT, "%.2f%%", (double) numerator / denominator * 100.0);
    }

    /** Same rule as {@link #pct} for the JSON report: null (JSON null) instead of a made-up 100%. */
    public static Double pctValue(int numerator, int denominator, int successfulCalls) {
        if (successfulCalls <= 0 || denominator <= 0) return null;
        return (double) numerator / denominator * 100.0;
    }
}
