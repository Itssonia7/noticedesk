package com.noticedesk.api.service.llm;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class ApiUsageLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageLogService.class);
    private final NamedParameterJdbcTemplate jdbcTemplate;

    // Hardcoded exchange rate as requested/assumed
    private static final BigDecimal USD_TO_INR = new BigDecimal("83.00");

    public ApiUsageLogService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void logUsage(String provider, String model, int inputTokens, int outputTokens) {
        try {
            BigDecimal[] costs = calculateCost(provider, model, inputTokens, outputTokens);
            BigDecimal costUsd = costs[0];
            BigDecimal costInr = costs[1];

            String sql = "INSERT INTO api_usage_logs (id, provider, model, input_tokens, output_tokens, cost_usd, cost_inr) " +
                         "VALUES (:id, :provider, :model, :inTokens, :outTokens, :costUsd, :costInr)";

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("provider", provider)
                    .addValue("model", model)
                    .addValue("inTokens", inputTokens)
                    .addValue("outTokens", outputTokens)
                    .addValue("costUsd", costUsd)
                    .addValue("costInr", costInr);

            jdbcTemplate.update(sql, params);
            log.info("Logged API Usage: provider={}, model={}, in={}, out={}, costInr={}", provider, model, inputTokens, outputTokens, costInr);
        } catch (Exception e) {
            log.error("Failed to log API usage for provider: {}", provider, e);
        }
    }

    private BigDecimal[] calculateCost(String provider, String model, int inputTokens, int outputTokens) {
        BigDecimal costUsd = BigDecimal.ZERO;
        
        if ("anthropic".equalsIgnoreCase(provider)) {
            if (model.contains("opus")) {
                // $15 / 1M in, $75 / 1M out
                costUsd = calculate(inputTokens, "15.00").add(calculate(outputTokens, "75.00"));
            } else if (model.contains("sonnet")) {
                // $3 / 1M in, $15 / 1M out
                costUsd = calculate(inputTokens, "3.00").add(calculate(outputTokens, "15.00"));
            } else if (model.contains("haiku")) {
                // $0.25 / 1M in, $1.25 / 1M out
                costUsd = calculate(inputTokens, "0.25").add(calculate(outputTokens, "1.25"));
            }
        } else if ("openai".equalsIgnoreCase(provider)) {
            if (model.contains("gpt-4-turbo") || model.contains("gpt-4o")) {
                // Approximate: $10 / 1M in, $30 / 1M out
                costUsd = calculate(inputTokens, "10.00").add(calculate(outputTokens, "30.00"));
            }
        }
        // Add gemini or other providers if needed

        BigDecimal costInr = costUsd.multiply(USD_TO_INR).setScale(4, RoundingMode.HALF_UP);
        return new BigDecimal[]{costUsd.setScale(6, RoundingMode.HALF_UP), costInr};
    }

    private BigDecimal calculate(int tokens, String pricePerMillionStr) {
        BigDecimal tokensBd = new BigDecimal(tokens);
        BigDecimal pricePerMillion = new BigDecimal(pricePerMillionStr);
        BigDecimal million = new BigDecimal("1000000");
        return tokensBd.multiply(pricePerMillion).divide(million, 6, RoundingMode.HALF_UP);
    }
}
