package com.noticedesk.api.service.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Mapping of a Gemini answer to {@link LlmResponse} (stop reason, failure, token counts); no live calls. */
class GeminiLlmProviderTest {

    private static final String MODEL = "gemini-test";

    @Test
    void stopIsAUsableEndTurn_withPromptAndOutputTokens() {
        LlmResponse r = GeminiLlmProvider.response("{\"a\": 1}", "STOP", 1200, 300, 500, MODEL);
        assertEquals("end_turn", r.stopReason());
        assertFalse(r.failed());
        assertEquals("gemini", r.providerName());
        assertEquals(MODEL, r.model());
        assertEquals(1200, r.inputTokensOrZero());
        assertEquals(800, r.outputTokensOrZero(), "answer plus thinking tokens, both billed as output");
        assertEquals(0, r.cacheReadTokensOrZero());
        assertEquals(0, r.cacheWriteTokensOrZero());
    }

    @Test
    void maxTokensIsATruncation_safetyARefusal_noTextAFailure() {
        LlmResponse cut = GeminiLlmProvider.response("{\"a\": ", "MAX_TOKENS", 10, 20, null, MODEL);
        assertTrue(cut.truncated());
        assertEquals(LlmResponse.FAILURE_MAX_TOKENS, cut.failureReason());
        assertEquals(20, cut.outputTokensOrZero());

        LlmResponse blocked = GeminiLlmProvider.response(null, "SAFETY", 10, null, null, MODEL);
        assertTrue(blocked.refused());

        LlmResponse empty = GeminiLlmProvider.response("  ", "STOP", 10, 0, null, MODEL);
        assertEquals(LlmResponse.FAILURE_NO_TEXT, empty.failureReason());
        assertFalse(empty.truncated());

        LlmResponse noUsage = GeminiLlmProvider.response("ok", null, null, null, null, MODEL);
        assertFalse(noUsage.failed());
        assertNull(noUsage.outputTokens());
    }
}
