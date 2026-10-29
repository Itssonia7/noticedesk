package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-notice limits on v7 model calls: a cap on high-stakes calls ({@code llm.high-stakes.max-calls-per-notice})
 * and on total spend ({@code llm.max-cost-inr-per-notice}). One instance per pipeline run; not shared
 * between notices.
 *
 * <p>The spend cap is a hard cap: before every billed call (first attempt and retry) the agent asks
 * {@link #refusalReason(String, Prompt, int)} with the call's prompt and the max_tokens it is about to
 * send. The call is refused when {@code spent + worst case > cap}. The worst case is cache-aware
 * (see {@link #estimate(ApiUsageLogService, Prompt, int)}):
 * <ul>
 *   <li>the cacheable prefix (system prompt + catalogue for matching; system prompt + notice prefix for the
 *       writers): its token count is the one the API reported for the same prefix earlier in this process
 *       (cache_creation / cache_read tokens), else {@code ceil(characters / 2)}. Priced at the cache-READ
 *       rate when this process wrote or read the same prefix (same model) within the cache's warm window
 *       ({@link PromptCacheRegistry}), otherwise at the cache-write rate (the first call of a run);</li>
 *   <li>the per-call part (user prompt): {@code ceil(characters / 2)} tokens (conservative: English/legal
 *       text runs about 4 characters per token, but a live matching call estimated at about 5,900 tokens
 *       with 3 characters per token used about 7,770) at the input price;</li>
 *   <li>output: the full max_tokens of the call (thinking included) at the output price.</li>
 * </ul>
 * With a cap set, a model MISSING from the price table is refused (its cost could not be capped); a model priced at
 * zero (a free tier) is in the table and allowed, its worst case being 0.
 */
@Slf4j
public final class NoticeCallBudget {

    public static final String HIGH_STAKES = "high_stakes";
    /** Characters per token assumed when estimating a prompt's input tokens (deliberately low = conservative). */
    public static final int CHARS_PER_TOKEN = 2;

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

    // ------------------------------------------------------------------ prompt shape and estimate

    /**
     * The input of one call split into the part the provider caches and the part sent fresh each time.
     *
     * @param model        the model the call is priced as (the requested model id)
     * @param prefixHash   {@link PromptCacheRegistry#hash} of the cacheable prefix text ("" when none)
     * @param prefixChars  characters of the cacheable prefix
     * @param perCallChars characters of the per-call part
     */
    public record Prompt(String model, String prefixHash, long prefixChars, long perCallChars) {

        /** Shape of a call whose cacheable prefix is {@code cacheablePrefix} and whose per-call text is {@code perCall}. */
        public static Prompt of(String model, String cacheablePrefix, String perCall) {
            String prefix = cacheablePrefix != null ? cacheablePrefix : "";
            return new Prompt(model, PromptCacheRegistry.hash(prefix), prefix.length(), perCall != null ? perCall.length() : 0);
        }

        /** Shape of a call with no cacheable prefix (everything priced as per-call input). */
        public static Prompt uncached(String model, long chars) {
            return new Prompt(model, "", 0, chars);
        }
    }

    /**
     * Worst-case breakdown of one call. {@code prefixPricing} is {@code cache_read} (prefix warm in this
     * process), {@code cache_write} (cold or unknown) or {@code none} (no prefix); {@code prefixMeasured}
     * is true when the prefix token count came from the API rather than the character estimate.
     */
    public record Estimate(BigDecimal totalInr, int prefixTokens, boolean prefixMeasured, String prefixPricing,
                           BigDecimal prefixInr, int perCallTokens, BigDecimal perCallInr, BigDecimal outputInr) {

        String describe() {
            return "prefix_tokens=" + prefixTokens + (prefixMeasured ? "(measured)" : "(est)")
                    + " prefix_pricing=" + prefixPricing + " prefix_INR=" + inr(prefixInr)
                    + " per_call_tokens=" + perCallTokens + " per_call_INR=" + inr(perCallInr)
                    + " output_INR=" + inr(outputInr) + " worst_case_INR=" + inr(totalInr);
        }
    }

    /** Input tokens assumed for a prompt of {@code chars} characters: {@code ceil(chars / CHARS_PER_TOKEN)}. */
    public static int estimateInputTokens(long chars) {
        long tokens = (Math.max(0, chars) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /**
     * Cache-aware worst case of one call (see the class comment), or null when the model has no configured
     * price. {@code maxTokens} must be the max_tokens sent with the call (retry cap included).
     */
    public static Estimate estimate(ApiUsageLogService pricing, Prompt prompt, int maxTokens) {
        if (pricing == null || prompt == null || !pricing.hasPrice(prompt.model())) return null;
        String model = prompt.model();
        AppProperties.Pricing.ModelPrice price = pricing.modelPrice(model);
        int out = Math.max(0, maxTokens);
        BigDecimal outputInr = pricing.calculateCost(model, 0, out, 0, 0)[1];

        int perCall = estimateInputTokens(prompt.perCallChars());
        BigDecimal perCallInr = pricing.calculateCost(model, perCall, 0, 0, 0)[1];

        boolean hasPrefix = prompt.prefixChars() > 0 && !prompt.prefixHash().isEmpty();
        int prefixTokens = 0;
        boolean measured = false;
        String prefixPricing = "none";
        BigDecimal prefixInr = BigDecimal.ZERO;
        if (hasPrefix) {
            PromptCacheRegistry cache = pricing.promptCache();
            Integer known = cache != null ? cache.measuredTokens(model, prompt.prefixHash()) : null;
            if (known != null && known > 0) {
                prefixTokens = known;
                measured = true;
            } else {
                prefixTokens = estimateInputTokens(prompt.prefixChars());
            }
            boolean warm = cache != null && cache.isWarm(model, prompt.prefixHash());
            BigDecimal asInput = pricing.calculateCost(model, prefixTokens, 0, 0, 0)[1];
            if (warm && price != null && price.getCacheReadPerMillion() > 0) {
                prefixPricing = "cache_read";
                prefixInr = pricing.calculateCost(model, 0, 0, prefixTokens, 0)[1];
            } else {
                // cold or unknown: the prefix is written to the cache (1.25x input); never below the input price
                prefixPricing = "cache_write";
                prefixInr = pricing.calculateCost(model, 0, 0, 0, prefixTokens)[1].max(asInput);
            }
        }
        BigDecimal total = outputInr.add(perCallInr).add(prefixInr);
        return new Estimate(total, prefixTokens, measured, prefixPricing, prefixInr, perCall, perCallInr, outputInr);
    }

    /**
     * Worst-case INR of a call whose whole input is one cold cacheable prefix (no registry knowledge: every
     * input token at the higher of the input and cache-write prices), or null when the model has no price.
     * Kept for callers that have no prefix split; the agents use {@link #estimate}.
     */
    public static BigDecimal worstCaseInr(ApiUsageLogService pricing, String model, long inputChars, int maxTokens) {
        if (pricing == null || !pricing.hasPrice(model)) return null;
        int in = estimateInputTokens(inputChars);
        int out = Math.max(0, maxTokens);
        BigDecimal asInput = pricing.calculateCost(model, in, out, 0, 0)[1];
        BigDecimal asCacheWrite = pricing.calculateCost(model, 0, out, 0, in)[1];
        return asInput.max(asCacheWrite);
    }

    // ------------------------------------------------------------------ checks

    /** Why a call for {@code agentKey} must not be made, or null when it may. */
    public synchronized String refusalReason(String agentKey) {
        if (maxCostInr != null && spentInr.compareTo(maxCostInr) >= 0) {
            return "per-notice cost cap reached (INR " + spentInr.setScale(2, RoundingMode.HALF_UP)
                    + " of " + maxCostInr + ")";
        }
        if (HIGH_STAKES.equals(agentKey) && highStakesCalls >= maxHighStakesCalls) {
            return "high-stakes call cap reached (" + highStakesCalls + " of " + maxHighStakesCalls + " calls)";
        }
        return null;
    }

    /**
     * As {@link #refusalReason(String, Prompt, int)} for a call with no cacheable prefix (the whole input
     * priced as per-call text).
     */
    public synchronized String refusalReason(String agentKey, String model, long inputChars, int maxTokens) {
        return refusalReason(agentKey, Prompt.uncached(model, inputChars), maxTokens);
    }

    /**
     * Why the call about to be made must not be made, or null when it may: the checks of
     * {@link #refusalReason(String)}, then the hard cost cap {@code spent + worst case > cap}. Logs the
     * worst-case breakdown of every call it checks ({@code [call-check]} / {@code [call-refused]}). Call
     * before every billed attempt, retries included.
     */
    public synchronized String refusalReason(String agentKey, Prompt prompt, int maxTokens) {
        String reason = refusalReason(agentKey);
        String model = prompt != null ? prompt.model() : null;
        Estimate est = estimate(pricing, prompt, maxTokens);
        String line = "step=" + agentKey + " model=" + model + " max_tokens=" + maxTokens + " "
                + (est != null ? est.describe() : "worst_case_INR=UNPRICED")
                + " spent_INR=" + inr(spentInr) + " cap_INR=" + (maxCostInr != null ? maxCostInr.toPlainString() : "none");
        if (reason == null && maxCostInr != null) {
            if (est == null) {
                reason = "no price configured for model " + model + "; its cost cannot be capped";
            } else if (spentInr.add(est.totalInr()).compareTo(maxCostInr) > 0) {
                reason = "per-notice cost cap: worst case of this call INR " + inr(est.totalInr()) + " + spent INR " + inr(spentInr)
                        + " exceeds the cap of INR " + maxCostInr.toPlainString();
            }
        }
        if (reason != null) {
            budgetRefusals++;
            lastRefusal = reason;
            log.warn("[call-refused] {} reason=\"{}\"", line, reason);
        } else {
            log.info("[call-check] {} allowed", line);
        }
        return reason;
    }

    /**
     * Why a call of a known fixed cost (e.g. one IndianKanoon search priced from configuration) must not be
     * made, or null when it may: refused when {@code spent + cost > cap}. A zero cost is always allowed
     * unless the cap is already reached.
     */
    public synchronized String refusalReasonForFixedCost(String step, BigDecimal costInr) {
        BigDecimal cost = costInr != null ? costInr : BigDecimal.ZERO;
        String reason = null;
        if (maxCostInr != null) {
            if (spentInr.compareTo(maxCostInr) >= 0) {
                reason = "per-notice cost cap reached (INR " + inr(spentInr) + " of " + maxCostInr + ")";
            } else if (spentInr.add(cost).compareTo(maxCostInr) > 0) {
                reason = "per-notice cost cap: this call INR " + inr(cost) + " + spent INR " + inr(spentInr)
                        + " exceeds the cap of INR " + maxCostInr.toPlainString();
            }
        }
        String line = "step=" + step + " cost_INR=" + inr(cost) + " spent_INR=" + inr(spentInr)
                + " cap_INR=" + (maxCostInr != null ? maxCostInr.toPlainString() : "none");
        if (reason != null) {
            budgetRefusals++;
            lastRefusal = reason;
            log.warn("[call-refused] {} reason=\"{}\"", line, reason);
        } else {
            log.info("[call-check] {} allowed", line);
        }
        return reason;
    }

    /** Number of calls refused by this budget so far (callers compare before/after a writer call). */
    public synchronized int budgetRefusals() {
        return budgetRefusals;
    }

    /** Reason of the last call refused by {@link #refusalReason(String, Prompt, int)}, or null. */
    public synchronized String lastRefusal() {
        return lastRefusal;
    }

    /** True when {@code reason} (from this budget) is the high-stakes call-count cap rather than a cost limit. */
    public static boolean isCallCountRefusal(String reason) {
        return reason != null && reason.startsWith("high-stakes call cap");
    }

    private static String inr(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ recording

    /** Counts one attempted call (made even when it failed or threw) and adds its cost when known. */
    public synchronized void recordCall(String agentKey, LlmResponse response, String fallbackModel) {
        recordCall(agentKey, response, fallbackModel, null);
    }

    /**
     * As {@link #recordCall(String, LlmResponse, String)}; with {@code prompt} the response's cache token
     * counts are also recorded in the {@link PromptCacheRegistry} for the prompt's prefix, so the next call
     * sharing it is priced at the cache-read rate with the measured token count.
     */
    public synchronized void recordCall(String agentKey, LlmResponse response, String fallbackModel, Prompt prompt) {
        if (HIGH_STAKES.equals(agentKey)) highStakesCalls++;
        if (response != null) {
            String model = response.model() != null && !response.model().isBlank() ? response.model() : fallbackModel;
            if (pricing != null && !pricing.hasPrice(model) && pricing.hasPrice(fallbackModel)) model = fallbackModel;
            addCost(model, response.inputTokensOrZero(), response.outputTokensOrZero(),
                    response.cacheReadTokensOrZero(), response.cacheWriteTokensOrZero());
            recordCacheUse(prompt, response);
        }
    }

    /**
     * Records in the shared registry that the prefix of {@code prompt} was just written to or read from the
     * provider's cache, with the token count the API reported. No-op when the response carries no cache
     * tokens (prefix too short to cache, or a provider without caching).
     */
    public void recordCacheUse(Prompt prompt, LlmResponse response) {
        if (pricing == null || pricing.promptCache() == null || prompt == null || response == null) return;
        int cacheTokens = response.cacheWriteTokensOrZero() + response.cacheReadTokensOrZero();
        if (cacheTokens <= 0 || prompt.prefixHash().isEmpty()) return;
        pricing.promptCache().recordUse(prompt.model(), prompt.prefixHash(), cacheTokens);
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
