package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Per-notice limits on v7 model calls: a cap on high-stakes calls ({@code llm.high-stakes.max-calls-per-notice})
 * and on total spend ({@code llm.max-cost-inr-per-notice}). One instance per pipeline run; not shared
 * between notices. Calls whose model has no configured price add no cost (a warning is logged).
 */
@Slf4j
public final class NoticeCallBudget {

    public static final String HIGH_STAKES = "high_stakes";

    private final int maxHighStakesCalls;
    private final BigDecimal maxCostInr;
    private final ApiUsageLogService pricing;

    private int highStakesCalls;
    private BigDecimal spentInr = BigDecimal.ZERO;

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

    /** Counts one attempted call (made even when it failed or threw) and adds its cost when known. */
    public synchronized void recordCall(String agentKey, LlmResponse response, String fallbackModel) {
        if (HIGH_STAKES.equals(agentKey)) highStakesCalls++;
        if (response != null) {
            String model = response.model() != null && !response.model().isBlank() ? response.model() : fallbackModel;
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

    public synchronized BigDecimal spentInr() {
        return spentInr;
    }

    public synchronized int highStakesCalls() {
        return highStakesCalls;
    }
}
