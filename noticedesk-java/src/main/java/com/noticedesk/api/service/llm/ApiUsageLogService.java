package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class ApiUsageLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageLogService.class);
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AppProperties properties;

    private final List<Consumer<UsageCall>> callListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CallOutcome>> outcomeListeners = new CopyOnWriteArrayList<>();
    /** Process-wide view of the provider's prompt cache (see {@link PromptCacheRegistry}); one per service bean. */
    private final PromptCacheRegistry promptCache = new PromptCacheRegistry();

    /** Provider / model labels under which IndianKanoon calls are logged (priced from {@code noticedesk.citation}). */
    public static final String IK_PROVIDER = "indiankanoon";
    public static final String IK_SEARCH = "ik_search";
    public static final String IK_FRAGMENT = "ik_fragment";

    public ApiUsageLogService(NamedParameterJdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * One billed model call, success or not. {@code costInr}/{@code costUsd} are null when the model has
     * no configured price. Carries no prompt or response text.
     */
    public record UsageCall(
            String step,
            int attempt,
            String provider,
            String model,
            String stopReason,
            String failureReason,
            int inputTokens,
            int cacheReadTokens,
            int cacheWriteTokens,
            int outputTokens,
            BigDecimal costUsd,
            BigDecimal costInr
    ) {}

    /**
     * Whether one model call attempt gave output the agent could use. {@code reason} is a short label
     * (failure reason, exception class or validation error, never response text); null when usable.
     */
    public record CallOutcome(String step, int attempt, boolean usable, String reason) {}

    /** The prompt-cache registry shared by every {@link NoticeCallBudget} that prices with this service. */
    public PromptCacheRegistry promptCache() {
        return promptCache;
    }

    /** Registers a listener told about every call logged through {@link #logCall}. Used by eval/live runners. */
    public void addCallListener(Consumer<UsageCall> listener) {
        callListeners.add(listener);
    }

    /** Registers a listener told the outcome of every model call attempt (see {@link #reportOutcome}). */
    public void addOutcomeListener(Consumer<CallOutcome> listener) {
        outcomeListeners.add(listener);
    }

    /**
     * Reported by the agents after each call attempt, including attempts that threw before a response.
     * A listener may stop the run by throwing {@link LlmFatalException}; it is passed on to the agent.
     */
    public void reportOutcome(String step, int attempt, boolean usable, String reason) {
        CallOutcome outcome = new CallOutcome(step, attempt, usable, reason);
        for (Consumer<CallOutcome> l : outcomeListeners) {
            try {
                l.accept(outcome);
            } catch (LlmFatalException e) {
                throw e;
            } catch (Exception e) {
                log.warn("call outcome listener failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Logs the usage of one model call from its response, whether or not the output was usable: the
     * call is billed either way. Output tokens include thinking tokens as reported by the API.
     */
    public void logCall(LlmResponse response, String fallbackModel, String step, int attempt, UUID tenantId, UUID noticeId) {
        if (response == null) return;
        String model = response.model() != null && !response.model().isBlank() ? response.model() : fallbackModel;
        // price by the requested model when the id the API reports has no configured price
        if (!hasPrice(model) && hasPrice(fallbackModel)) model = fallbackModel;
        String provider = response.providerName() != null ? response.providerName() : "anthropic";
        int in = response.inputTokensOrZero();
        int out = response.outputTokensOrZero();
        int cRead = response.cacheReadTokensOrZero();
        int cWrite = response.cacheWriteTokensOrZero();

        logUsage(provider, model, in, out, cRead, cWrite, step, tenantId, noticeId);

        BigDecimal usd = null;
        BigDecimal inr = null;
        try {
            BigDecimal[] costs = calculateCost(model, in, out, cRead, cWrite);
            usd = costs[0];
            inr = costs[1];
        } catch (Exception e) {
            log.warn("No price configured for model {}; call cost not computed", model);
        }
        UsageCall call = new UsageCall(step, attempt, provider, model, response.stopReason(), response.failureReason(),
                in, cRead, cWrite, out, usd, inr);
        for (Consumer<UsageCall> l : callListeners) {
            try {
                l.accept(call);
            } catch (Exception e) {
                log.warn("usage call listener failed: {}", e.getMessage());
            }
        }
    }

    public void logUsage(String provider, String model, int inputTokens, int outputTokens) {
        logUsage(provider, model, inputTokens, outputTokens, 0, 0, "general", null, null);
    }

    public void logUsage(String provider, String model, int inputTokens, int outputTokens, String step, UUID tenantId, UUID noticeId) {
        logUsage(provider, model, inputTokens, outputTokens, 0, 0, step, tenantId, noticeId);
    }

    public void logUsage(
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheWriteTokens,
            String step,
            UUID tenantId,
            UUID noticeId) {
        try {
            BigDecimal[] costs = calculateCost(model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
            insertUsageRow(provider, model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, step,
                    tenantId, noticeId, costs[0], costs[1]);
            log.info("Logged API Usage: step={} provider={} model={} in={} out={} cacheRead={} cacheWrite={} costInr={}",
                    step, provider, model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costs[1]);
        } catch (Exception e) {
            log.error("Failed to log API usage for model: {}", model, e);
        }
    }

    private void insertUsageRow(String provider, String model, int inputTokens, int outputTokens, int cacheReadTokens,
                                int cacheWriteTokens, String step, UUID tenantId, UUID noticeId,
                                BigDecimal costUsd, BigDecimal costInr) {
        if (jdbcTemplate == null) return;
        try {
            String sql = """
                    INSERT INTO api_usage_logs (
                        id, tenant_id, notice_id, provider, model, step,
                        input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                        cost_usd, cost_inr
                    ) VALUES (
                        :id, CAST(:tenantId AS UUID), CAST(:noticeId AS UUID), :provider, :model, :step,
                        :inTokens, :outTokens, :cacheReadTokens, :cacheWriteTokens,
                        :costUsd, :costInr
                    )
                    """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("tenantId", tenantId != null ? tenantId.toString() : null)
                    .addValue("noticeId", noticeId != null ? noticeId.toString() : null)
                    .addValue("provider", provider)
                    .addValue("model", model)
                    .addValue("step", step)
                    .addValue("inTokens", inputTokens)
                    .addValue("outTokens", outputTokens)
                    .addValue("cacheReadTokens", cacheReadTokens)
                    .addValue("cacheWriteTokens", cacheWriteTokens)
                    .addValue("costUsd", costUsd)
                    .addValue("costInr", costInr);
            jdbcTemplate.update(sql, params);
        } catch (Exception e) {
            log.error("Failed to insert api_usage_logs row for step {}", step, e);
        }
    }

    /**
     * Configured INR cost of one IndianKanoon call: {@code citation.indiankanoon.cost-inr-per-search} for
     * {@link #IK_SEARCH}, {@code cost-inr-per-fragment} for {@link #IK_FRAGMENT}. Zero until set from the
     * firm's IndianKanoon plan.
     */
    public BigDecimal indianKanoonCostInr(String kind) {
        AppProperties.Citation c = properties != null ? properties.getCitation() : null;
        AppProperties.Citation.IndianKanoon ik = c != null ? c.getIndiankanoon() : null;
        double v = ik == null ? 0.0 : IK_FRAGMENT.equals(kind) ? ik.getCostInrPerFragment() : ik.getCostInrPerSearch();
        return v > 0 ? BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * Logs one IndianKanoon call ({@link #IK_SEARCH} or {@link #IK_FRAGMENT}) at its configured INR cost: a
     * usage row with the cost and one {@link UsageCall} to the listeners (so a run ledger shows it). Returns
     * the cost so the caller can count it against the per-notice cap.
     */
    public BigDecimal logIndianKanoonCall(String kind, UUID tenantId, UUID noticeId) {
        BigDecimal inr = indianKanoonCostInr(kind);
        double rate = (properties != null && properties.getPricing() != null) ? properties.getPricing().getInrPerUsd() : 88.0;
        BigDecimal usd = rate > 0 ? inr.divide(BigDecimal.valueOf(rate), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        insertUsageRow(IK_PROVIDER, kind, 1, 0, 0, 0, kind, tenantId, noticeId, usd, inr);
        log.info("Logged API Usage: step={} provider={} calls=1 costInr={}", kind, IK_PROVIDER, inr.toPlainString());
        UsageCall call = new UsageCall(kind, 1, IK_PROVIDER, kind, "ok", null, 1, 0, 0, 0, usd, inr);
        for (Consumer<UsageCall> l : callListeners) {
            try {
                l.accept(call);
            } catch (Exception e) {
                log.warn("usage call listener failed: {}", e.getMessage());
            }
        }
        return inr;
    }

    /** The configured price of {@code model} (exact key or dated snapshot of a key), or null. */
    public AppProperties.Pricing.ModelPrice modelPrice(String model) {
        return model != null ? resolvePrice(model) : null;
    }

    public BigDecimal[] calculateCost(String model, int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Unconfigured pricing model: null or empty model string");
        }

        AppProperties.Pricing.ModelPrice modelPrice = resolvePrice(model);
        if (modelPrice == null) {
            throw new IllegalStateException("Unconfigured pricing model: " + model + ". Please configure pricing in application.yml under noticedesk.pricing.models.");
        }

        double inrRate = (properties != null && properties.getPricing() != null) ? properties.getPricing().getInrPerUsd() : 88.0;
        BigDecimal rateInr = BigDecimal.valueOf(inrRate);

        BigDecimal inputCost = calculateTokens(inputTokens, modelPrice.getInputPerMillion());
        BigDecimal outputCost = calculateTokens(outputTokens, modelPrice.getOutputPerMillion());
        BigDecimal cacheReadCost = calculateTokens(cacheReadTokens, modelPrice.getCacheReadPerMillion());
        BigDecimal cacheWriteCost = calculateTokens(cacheWriteTokens, modelPrice.getCacheWritePerMillion());

        BigDecimal costUsd = inputCost.add(outputCost).add(cacheReadCost).add(cacheWriteCost);
        BigDecimal costInr = costUsd.multiply(rateInr).setScale(4, RoundingMode.HALF_UP);

        return new BigDecimal[]{costUsd.setScale(6, RoundingMode.HALF_UP), costInr};
    }

    /** True when {@link #calculateCost} can price {@code model}. */
    public boolean hasPrice(String model) {
        return model != null && !model.isBlank() && resolvePrice(model) != null;
    }

    /**
     * Price for {@code model}: the exact key, else the longest configured key the model id extends with
     * a dash (a dated snapshot id such as {@code <key>-20260101} returned by the API).
     */
    private AppProperties.Pricing.ModelPrice resolvePrice(String model) {
        if (properties == null || properties.getPricing() == null || properties.getPricing().getModels() == null) return null;
        java.util.Map<String, AppProperties.Pricing.ModelPrice> prices = properties.getPricing().getModels();
        String m = model.trim();
        AppProperties.Pricing.ModelPrice exact = prices.get(m);
        if (exact != null) return exact;
        String best = null;
        for (String key : prices.keySet()) {
            if (m.startsWith(key + "-") && (best == null || key.length() > best.length())) best = key;
        }
        return best != null ? prices.get(best) : null;
    }

    private BigDecimal calculateTokens(int tokens, double pricePerMillion) {
        if (tokens <= 0 || pricePerMillion <= 0.0) {
            return BigDecimal.ZERO;
        }
        BigDecimal tokensBd = BigDecimal.valueOf(tokens);
        BigDecimal priceBd = BigDecimal.valueOf(pricePerMillion);
        BigDecimal million = new BigDecimal("1000000");
        return tokensBd.multiply(priceBd).divide(million, 6, RoundingMode.HALF_UP);
    }
}
