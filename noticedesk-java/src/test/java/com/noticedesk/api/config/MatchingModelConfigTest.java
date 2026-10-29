package com.noticedesk.api.config;

import com.noticedesk.api.service.llm.LlmException;
import com.noticedesk.api.service.llm.LlmFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchingModelConfigTest {

    @Test
    void testMissingLlmModelMatchingFailsFastWithExplicitException() {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching(""); // Empty model-matching

        LlmFactory factory = new LlmFactory(props);

        LlmException ex = assertThrows(LlmException.class, () -> factory.getLlmForAgent("matching"));
        assertTrue(ex.getMessage().contains("LLM_MODEL_MATCHING"), "Exception message must explicitly mention LLM_MODEL_MATCHING");
    }

    @Test
    void testConfiguredLlmModelMatchingSucceeds() {
        AppProperties props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey("dummy-key");
        props.getLlm().setModelMatching("claude-sonnet-5-5");

        LlmFactory factory = new LlmFactory(props);

        assertDoesNotThrow(() -> factory.getLlmForAgent("matching"));
        assertEquals("claude-sonnet-5-5", props.getLlm().getModelMatching());
    }
}
