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

    /** Registers a listener told about every call logged through {@link #logCall}. Used by eval/live runners. */
    public void addCallListener(Consumer<UsageCall> listener) {
        callListeners.add(listener);
    }

    /**
     * Logs the usage of one model call from its response, whether or not the output was usable: the
     * call is billed either way. Output tokens include thinking tokens as reported by the API.
     */
    public void logCall(LlmResponse response, String fallbackModel, String step, int attempt, UUID tenantId, UUID noticeId) {
        if (response == null) return;
        String model = response.model() != null && !response.model().isBlank() ? response.model() : fallbackModel;
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
            BigDecimal costUsd = costs[0];
            BigDecimal costInr = costs[1];

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

            if (jdbcTemplate != null) {
                jdbcTemplate.update(sql, params);
            }
            log.info("Logged API Usage: step={} provider={} model={} in={} out={} cacheRead={} cacheWrite={} costInr={}",
                    step, provider, model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costInr);
        } catch (Exception e) {
            log.error("Failed to log API usage for model: {}", model, e);
        }
    }

    public BigDecimal[] calculateCost(String model, int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Unconfigured pricing model: null or empty model string");
        }

        AppProperties.Pricing.ModelPrice modelPrice = null;
        if (properties != null && properties.getPricing() != null && properties.getPricing().getModels() != null) {
            modelPrice = properties.getPricing().getModels().get(model.trim());
        }

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
