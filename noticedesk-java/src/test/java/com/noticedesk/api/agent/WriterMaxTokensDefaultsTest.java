package com.noticedesk.api.agent;

import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b38) Writer max_tokens defaults. A live run on a synthetic Section 74 notice refused two partial-drafting
 * calls because the hard cost cap reserved 16,000 output tokens each (worst case INR 15.10 with INR 11.93 spent
 * and a cap of INR 24.86), although a real per-issue draft is about 1,500 to 3,000 output tokens. The defaults are
 * now 6000 / retry 10000; the cap itself stays hard. Offline, pricing from application.yml.
 */
class WriterMaxTokensDefaultsTest {

    private static final String SONNET = "claude-sonnet-5-5";
    /** About 2,000 input tokens by the 2-chars-per-token estimate (the live call's input was ~INR 1). */
    private static final long INPUT_CHARS = 4_000;

    private final AppProperties props = TestAppProperties.fromApplicationYml();
    private final ApiUsageLogService pricing = new ApiUsageLogService(null, props);

    private NoticeCallBudget budget(String cap, String spent) {
        NoticeCallBudget b = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal(cap), pricing);
        b.addSpentInr(new BigDecimal(spent));
        return b;
    }

    @Test
    void writerDefaults_are6000_retry10000_inYmlAndJava() {
        AppProperties java = new AppProperties();
        for (String agent : new String[] {"partial_drafting", "new_issue", "high_stakes"}) {
            assertEquals(6000, props.getLlm().maxTokensForAgent(agent, false), agent + " yml");
            assertEquals(10000, props.getLlm().maxTokensForAgent(agent, true), agent + " yml retry");
            assertEquals(6000, java.getLlm().maxTokensForAgent(agent, false), agent + " java");
            assertEquals(10000, java.getLlm().maxTokensForAgent(agent, true), agent + " java retry");
        }
    }

    @Test
    void liveRunCase_partialDraftingCallFitsUnderTheCap_withTheNewDefault() {
        int maxTokens = props.getLlm().maxTokensForAgent("partial_drafting", false);
        // the old reservation (16000) is refused, as in the live run
        assertNotNull(budget("24.8552", "11.93").refusalReason("partial_drafting", SONNET, INPUT_CHARS, 16000));
        // the new default is allowed
        NoticeCallBudget b = budget("24.8552", "11.93");
        assertNull(b.refusalReason("partial_drafting", SONNET, INPUT_CHARS, maxTokens));
        assertEquals(0, b.budgetRefusals());
    }

    @Test
    void capStaysHard_andTheRefusalReasonIsInPlainWords() {
        NoticeCallBudget b = budget("24.8552", "21.00");
        String reason = b.refusalReason("partial_drafting", SONNET, INPUT_CHARS,
                props.getLlm().maxTokensForAgent("partial_drafting", false));
        assertNotNull(reason);
        assertTrue(reason.startsWith("per-notice cost cap: worst case of this call INR "), reason);
        assertTrue(reason.contains("exceeds the cap of INR 24.8552"), reason);
        assertEquals(reason, b.lastRefusal());
    }
}
