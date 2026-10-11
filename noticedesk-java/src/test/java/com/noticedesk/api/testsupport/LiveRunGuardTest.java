package com.noticedesk.api.testsupport;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.ApiUsageLogService.CallOutcome;
import com.noticedesk.api.service.llm.LlmFatalException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The live runners' cost safeguards, checked offline. */
class LiveRunGuardTest {

    private static LiveRunGuard guard(Map<String, String> env) {
        return new LiveRunGuard(env::get);
    }

    @Test
    void defaults_are_testCaps() {
        LiveRunGuard g = guard(Map.of());
        assertEquals(0, new BigDecimal("50").compareTo(g.maxRunCostInr()));
        assertEquals(0, new BigDecimal("30").compareTo(g.maxNoticeCostInr()));
        assertEquals(2, g.maxConsecutiveFailures());
        assertFalse(g.smoke());
    }

    @Test
    void envOverrides_areRead_andBadValuesFallBack() {
        LiveRunGuard g = guard(Map.of("LIVE_MAX_RUN_COST_INR", "20", "LIVE_MAX_NOTICE_COST_INR", "abc",
                "LIVE_MAX_CONSECUTIVE_FAILURES", "3", "LIVE_SMOKE", "TRUE"));
        assertEquals(0, new BigDecimal("20").compareTo(g.maxRunCostInr()));
        assertEquals(0, new BigDecimal("30").compareTo(g.maxNoticeCostInr()));
        assertEquals(3, g.maxConsecutiveFailures());
        assertTrue(g.smoke());
    }

    @Test
    void twoConsecutiveFailures_abortTheRun_aSuccessResetsTheCount() {
        LiveRunGuard g = guard(Map.of());
        g.onOutcome(new CallOutcome("new_issue", 1, false, "max_tokens"));
        g.onOutcome(new CallOutcome("new_issue", 2, true, null));
        assertEquals(0, g.consecutiveFailures());

        g.onOutcome(new CallOutcome("high_stakes", 1, false, "invalid_output"));
        LlmFatalException e = assertThrows(LlmFatalException.class,
                () -> g.onOutcome(new CallOutcome("high_stakes", 2, false, "invalid_output")));
        assertTrue(e.getMessage().startsWith("2 consecutive failed or unusable model calls"), e.getMessage());
    }

    @Test
    void abort_reachesTheAgent_throughReportOutcome() {
        ApiUsageLogService usage = new ApiUsageLogService(null, new AppProperties());
        guard(Map.of("LIVE_MAX_CONSECUTIVE_FAILURES", "1")).attach(usage);
        assertThrows(LlmFatalException.class, () -> usage.reportOutcome("matching", 1, false, "no_text_block"));
    }

    @Test
    void commitCheck_prefixMatch_refusesStaleHead_warnsWithoutGit() {
        String head = "ff33536abcdef0123456789abcdef0123456789";
        assertTrue(guard(Map.of()).checkCommit(head).allowed());
        assertTrue(guard(Map.of("LIVE_EXPECTED_COMMIT", "ff33536")).checkCommit(head).allowed());
        assertTrue(guard(Map.of("LIVE_EXPECTED_COMMIT", "FF33536ABC")).checkCommit(head).allowed());

        LiveRunGuard.CommitCheck stale = guard(Map.of("LIVE_EXPECTED_COMMIT", "948b7f3")).checkCommit(head);
        assertFalse(stale.allowed());
        assertTrue(stale.message().contains("HEAD is ff33536"), stale.message());
        assertFalse(guard(Map.of("LIVE_EXPECTED_COMMIT", "ff3")).checkCommit(head).allowed(), "too short to be safe");

        LiveRunGuard.CommitCheck noGit = guard(Map.of("LIVE_EXPECTED_COMMIT", "ff33536")).checkCommit(null);
        assertTrue(noGit.allowed());
        assertTrue(noGit.message().startsWith("WARNING"));
    }

    @Test
    void model_envWinsOverConfig_blankEnvIgnored() {
        Map<String, String> env = new HashMap<>();
        env.put("LLM_MODEL_NEW_ISSUE", "env-model");
        env.put("LLM_MODEL_HIGH_STAKES", "  ");
        LiveRunGuard g = guard(env);
        assertEquals("env-model", g.model("LLM_MODEL_NEW_ISSUE", "config-model"));
        assertEquals("config-model", g.model("LLM_MODEL_HIGH_STAKES", "config-model"));
        assertNull(g.model("LLM_MODEL_MATCHING", ""));
    }
}
