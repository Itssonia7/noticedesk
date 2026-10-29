package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService.UsageCall;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Prices and per-agent defaults as bound from the real application.yml. */
class ApiUsagePricingTest {

    // fixed usage block: input 1,000 / output 5,000 (thinking included) / cache read 20,000 / cache write 10,000
    private static final int IN = 1_000, OUT = 5_000, C_READ = 20_000, C_WRITE = 10_000;

    private final AppProperties props = TestAppProperties.fromApplicationYml();
    private final ApiUsageLogService service = new ApiUsageLogService(null, props);

    @Test
    void sonnet55_pricing() {
        // USD = 1000*2.00 + 5000*10.00 + 20000*0.20 + 10000*2.50 (=1.25x input), per million
        //     = 0.002 + 0.050 + 0.004 + 0.025 = 0.081 USD -> x 88 = 7.1280 INR
        BigDecimal[] cost = service.calculateCost("claude-sonnet-5-5", IN, OUT, C_READ, C_WRITE);
        assertEquals(0, new BigDecimal("0.081000").compareTo(cost[0]), "USD " + cost[0]);
        assertEquals(0, new BigDecimal("7.1280").compareTo(cost[1]), "INR " + cost[1]);
    }

    @Test
    void opus55_pricing() {
        // USD = 0.004 + 0.100 + 0.004 + 0.050 (cache write 5.00 = 1.25x 4.00) = 0.158 USD -> 13.9040 INR
        BigDecimal[] cost = service.calculateCost("claude-opus-5-5", IN, OUT, C_READ, C_WRITE);
        assertEquals(0, new BigDecimal("0.158000").compareTo(cost[0]), "USD " + cost[0]);
        assertEquals(0, new BigDecimal("13.9040").compareTo(cost[1]), "INR " + cost[1]);
    }

    @Test
    void cacheWritePrice_isOnePointTwoFiveTimesInput() {
        for (String m : List.of("claude-sonnet-5-5", "claude-opus-5-5")) {
            AppProperties.Pricing.ModelPrice p = props.getPricing().getModels().get(m);
            assertNotNull(p, m);
            assertEquals(p.getInputPerMillion() * 1.25, p.getCacheWritePerMillion(), 1e-9, m);
            assertEquals(0.20, p.getCacheReadPerMillion(), 1e-9, m);
        }
    }

    @Test
    void failedCall_isStillLoggedAndPriced() {
        List<UsageCall> seen = new ArrayList<>();
        service.addCallListener(seen::add);
        LlmResponse truncated = new LlmResponse("", "claude-sonnet-5-5", "anthropic", IN, OUT, "max_tokens",
                C_WRITE, C_READ, LlmResponse.FAILURE_MAX_TOKENS);

        service.logCall(truncated, "claude-sonnet-5-5", "matching", 1, null, null);

        assertEquals(1, seen.size());
        UsageCall c = seen.get(0);
        assertEquals("max_tokens", c.stopReason());
        assertEquals(1, c.attempt());
        assertEquals(0, new BigDecimal("7.1280").compareTo(c.costInr()));
    }

    @Test
    void perAgentEffortAndMaxTokens_defaultsFromYml() {
        AppProperties.Llm llm = props.getLlm();
        // these assertions hold when the LLM_EFFORT_* / LLM_MAX_TOKENS_* env vars are not set
        if (System.getenv("LLM_EFFORT_MATCHING") == null) assertEquals("medium", llm.effortForAgent("matching"));
        if (System.getenv("LLM_EFFORT_PARTIAL_DRAFTING") == null) assertEquals("medium", llm.effortForAgent("partial_drafting"));
        if (System.getenv("LLM_EFFORT_NEW_ISSUE") == null) assertEquals("medium", llm.effortForAgent("new_issue"));
        if (System.getenv("LLM_EFFORT_HIGH_STAKES") == null) assertEquals("medium", llm.effortForAgent("high_stakes"));
        // prompt v3.1 writes boundary lines and not_this_if_checked (live average ~5,000 output tokens; one notice hit 8,000)
        if (System.getenv("LLM_MAX_TOKENS_MATCHING") == null) assertEquals(12000, llm.maxTokensForAgent("matching", false));
        if (System.getenv("LLM_MAX_TOKENS_MATCHING_RETRY") == null) assertEquals(20000, llm.maxTokensForAgent("matching", true));
        assertTrue(llm.maxTokensForAgent("matching", true) > llm.maxTokensForAgent("matching", false));
        for (String a : List.of("partial_drafting", "new_issue", "high_stakes", "template_variables")) {
            assertTrue(llm.maxTokensForAgent(a, true) > llm.maxTokensForAgent(a, false), a);
        }
        // the template variable step: a lookup task on the partial-drafting model, low effort, a short JSON answer
        if (System.getenv("LLM_EFFORT_TEMPLATE_VARIABLES") == null) assertEquals("low", llm.effortForAgent("template_variables"));
        if (System.getenv("LLM_MAX_TOKENS_TEMPLATE_VARIABLES") == null) assertEquals(2000, llm.maxTokensForAgent("template_variables", false));
        if (System.getenv("LLM_MAX_TOKENS_TEMPLATE_VARIABLES_RETRY") == null) assertEquals(4000, llm.maxTokensForAgent("template_variables", true));
        assertEquals(props.modelForAgent("partial_drafting", "anthropic"), props.modelForAgent("template_variables", "anthropic"),
                "the variable step runs on the partial-drafting model: nothing new to price");
        if (System.getenv("ANTHROPIC_EFFORT_MODELS") == null) {
            assertTrue(llm.getAnthropic().getEffortModels().containsAll(List.of("claude-sonnet-5-5", "claude-opus-5-5")));
        }
    }

    @Test
    void emptyMaxTokens_fallsBackToDefault() {
        AppProperties.Llm llm = new AppProperties.Llm();
        llm.setMaxTokensMatching(null);
        llm.setMaxTokensMatchingRetry(null);
        assertEquals(12000, llm.maxTokensForAgent("matching", false));
        assertEquals(20000, llm.maxTokensForAgent("matching", true));
    }

    @Test
    void writerModelOverride_toAnotherConfiguredModel_isPriced() {
        // e.g. LLM_MODEL_NEW_ISSUE / LLM_MODEL_HIGH_STAKES pointed at the matching model for a cheap plumbing run
        String cheap = props.getPricing().getModels().keySet().iterator().next();
        AppProperties p = TestAppProperties.fromApplicationYml();
        p.getLlm().setModelNewIssue(cheap);
        p.getLlm().setModelHighStakes(cheap);
        ApiUsageLogService s = new ApiUsageLogService(null, p);
        assertTrue(s.hasPrice(p.modelForAgent("new_issue", "anthropic")));
        assertTrue(s.hasPrice(p.modelForAgent("high_stakes", "anthropic")));
    }

    @Test
    void datedSnapshotId_isPricedAsItsBaseModel() {
        String base = props.getPricing().getModels().keySet().iterator().next();
        BigDecimal[] exact = service.calculateCost(base, IN, OUT, C_READ, C_WRITE);
        BigDecimal[] dated = service.calculateCost(base + "-20991231", IN, OUT, C_READ, C_WRITE);
        assertEquals(0, exact[1].compareTo(dated[1]));
        assertFalse(service.hasPrice("unknown-model-x"));
        assertFalse(service.hasPrice(base + "x"), "only a dash-separated suffix counts as a snapshot of the key");
    }

    @Test
    void unpricedResponseModel_isPricedByTheRequestedModel() {
        String requested = props.getPricing().getModels().keySet().iterator().next();
        List<UsageCall> seen = new ArrayList<>();
        service.addCallListener(seen::add);
        service.logCall(new LlmResponse("{}", "alias-reported-by-api", "anthropic", IN, OUT), requested, "new_issue", 1, null, null);
        assertEquals(requested, seen.get(0).model());
        assertNotNull(seen.get(0).costInr());
    }
}
