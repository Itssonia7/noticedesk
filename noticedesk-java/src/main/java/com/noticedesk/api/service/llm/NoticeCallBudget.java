package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Per-notice limits on v7 model calls: a cap on high-stakes calls ({@code llm.high-stakes.max-calls-per-notice})
 * and on total spend ({@code llm.max-cost-inr-per-notice}). One instance per pipeline run; not shared
 * between notices.
 *
 * <p>The spend cap is a hard cap: before every billed call (first attempt and retry) the agent asks
 * {@link #refusalReason(String, String, long, int)} with the call's prompt size and the max_tokens it is
 * about to send. The call is refused when {@code spent + worst case > cap}, where the worst case is
 * <ul>
 *   <li>input: {@code ceil(prompt characters / 3)} tokens (conservative: English/legal text runs about 4
 *       characters per token), every token priced at the higher of the input and cache-write prices, so
 *       a prompt that is written to the cache is covered too;</li>
 *   <li>output: the full max_tokens of the call (thinking included) at the output price.</li>
 * </ul>
 * With a cap set, a model with no configured price is refused (its cost could not be capped).
 */
@Slf4j
public final class NoticeCallBudget {

    public static final String HIGH_STAKES = "high_stakes";
    /** Characters per token assumed when estimating a prompt's input tokens (deliberately low = conservative). */
    public static final int CHARS_PER_TOKEN = 3;

    private final int maxHighStakesCalls;
    private final BigDecimal maxCostInr;
    private final ApiUsageLogService pricing;

    private int highStakesCalls;
    private BigDecimal spentInr = BigDecimal.ZERO;
    private int budgetRefusals;
    private String lastRefusal;

    public NoticeCallBudget(int maxHighStakesCalls, BigDecimal maxCostInr, ApiUsageLogService pricing) {
        this.maxHighStakesCalls = maxHighStakesCalls;
        this.maxCostInr = maxCostInr;
        this.pricing = pricing;
    }

    /** Limits from {@code noticedesk.llm.*}; a missing or non-positive value means no limit. */
    public static NoticeCallBudget fromProperties(AppProperties properties, ApiUsageLogService pricing) {
        AppProperties.Llm llm = properties != null ? properties.getLlm() : null;
        Integer calls = llm != null && llm.getHighStakes() != null ? llm.getHighStakes().getMaxCallsPerNotice() : null;
        Double cost = llm != null ? llm.getMaxCostInrPerNotice() : null;
        return new NoticeCallBudget(
                calls != null && calls > 0 ? calls : Integer.MAX_VALUE,
                cost != null && cost > 0 ? BigDecimal.valueOf(cost) : null,
                pricing);
    }

    public static NoticeCallBudget unlimited() {
        return new NoticeCallBudget(Integer.MAX_VALUE, null, null);
    }

    /** Why a call for {@code agentKey} must not be made, or null when it may. */
    public synchronized String refusalReason(String agentKey) {
        if (maxCostInr != null && spentInr.compareTo(maxCostInr) >= 0) {
            return "per-notice cost cap reached (INR " + spentInr.setScale(2, java.math.RoundingMode.HALF_UP)
                    + " of " + maxCostInr + ")";
        }
        if (HIGH_STAKES.equals(agentKey) && highStakesCalls >= maxHighStakesCalls) {
            return "high-stakes call cap reached (" + highStakesCalls + " of " + maxHighStakesCalls + " calls)";
        }
        return null;
    }

    /** Input tokens assumed for a prompt of {@code chars} characters: {@code ceil(chars / CHARS_PER_TOKEN)}. */
    public static int estimateInputTokens(long chars) {
        long tokens = (Math.max(0, chars) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /**
     * Worst-case INR cost of one call (see the class comment), or null when the model has no configured
     * price. {@code maxTokens} must be the max_tokens sent with the call (retry cap included).
     */
    public static BigDecimal worstCaseInr(ApiUsageLogService pricing, String model, long inputChars, int maxTokens) {
        if (pricing == null || !pricing.hasPrice(model)) return null;
        int in = estimateInputTokens(inputChars);
        int out = Math.max(0, maxTokens);
        BigDecimal asInput = pricing.calculateCost(model, in, out, 0, 0)[1];
        BigDecimal asCacheWrite = pricing.calculateCost(model, 0, out, 0, in)[1];
        return asInput.max(asCacheWrite);
    }

    /**
     * Why the call about to be made must not be made, or null when it may: the checks of
     * {@link #refusalReason(String)}, then the hard cost cap {@code spent + worst case > cap}. Logs the
     * worst-case estimate of every call it checks. Call before every billed attempt, retries included.
     */
    public synchronized String refusalReason(String agentKey, String model, long inputChars, int maxTokens) {
        String reason = refusalReason(agentKey);
        BigDecimal worst = worstCaseInr(pricing, model, inputChars, maxTokens);
        String estimate = "step=" + agentKey + " model=" + model + " est_input_tokens=" + estimateInputTokens(inputChars)
                + " max_tokens=" + maxTokens + " worst_case_INR=" + (worst != null ? inr(worst) : "UNPRICED")
                + " spent_INR=" + inr(spentInr) + " cap_INR=" + (maxCostInr != null ? maxCostInr.toPlainString() : "none");
        if (reason == null && maxCostInr != null) {
            if (worst == null) {
                reason = "no price configured for model " + model + "; its cost cannot be capped";
            } else if (spentInr.add(worst).compareTo(maxCostInr) > 0) {
                reason = "per-notice cost cap: worst case of this call INR " + inr(worst) + " + spent INR " + inr(spentInr)
                        + " exceeds the cap of INR " + maxCostInr.toPlainString();
            }
        }
        if (reason != null) {
            budgetRefusals++;
            lastRefusal = reason;
            log.warn("[call-refused] {} reason=\"{}\"", estimate, reason);
        } else {
            log.info("[call-check] {} allowed", estimate);
        }
        return reason;
    }

    /** Number of calls refused by this budget so far (callers compare before/after a writer call). */
    public synchronized int budgetRefusals() {
        return budgetRefusals;
    }

    /** Reason of the last call refused by {@link #refusalReason(String, String, long, int)}, or null. */
    public synchronized String lastRefusal() {
        return lastRefusal;
    }

    /** True when {@code reason} (from this budget) is the high-stakes call-count cap rather than a cost limit. */
    public static boolean isCallCountRefusal(String reason) {
        return reason != null && reason.startsWith("high-stakes call cap");
    }

    private static String inr(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Counts one attempted call (made even when it failed or threw) and adds its cost when known. */
    public synchronized void recordCall(String agentKey, LlmResponse response, String fallbackModel) {
        if (HIGH_STAKES.equals(agentKey)) highStakesCalls++;
        if (response != null) {
            String model = response.model() != null && !response.model().isBlank() ? response.model() : fallbackModel;
            if (pricing != null && !pricing.hasPrice(model) && pricing.hasPrice(fallbackModel)) model = fallbackModel;
            addCost(model, response.inputTokensOrZero(), response.outputTokensOrZero(),
                    response.cacheReadTokensOrZero(), response.cacheWriteTokensOrZero());
        }
    }

    /** Adds the cost of calls made outside the writers (Stage 1 matching). */
    public synchronized void addCost(String model, int in, int out, int cacheRead, int cacheWrite) {
        if (pricing == null || model == null) return;
        try {
            spentInr = spentInr.add(pricing.calculateCost(model, in, out, cacheRead, cacheWrite)[1]);
        } catch (Exception e) {
            log.warn("No price configured for model {}; its cost is not counted against the per-notice cap", model);
        }
    }

    /** Adds a cost already known in INR (spend made before this budget existed, e.g. earlier in the notice). */
    public synchronized void addSpentInr(BigDecimal inr) {
        if (inr != null && inr.signum() > 0) spentInr = spentInr.add(inr);
    }

    public synchronized BigDecimal spentInr() {
        return spentInr;
    }

    public synchronized int highStakesCalls() {
        return highStakesCalls;
    }
}
