package com.noticedesk.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppPropertiesModelDefaultsTest {

    @Test
    void testAppPropertiesModelFieldsDefaultToEmpty() {
        AppProperties props = new AppProperties();

        assertEquals("", props.getLlm().getModelExtraction(), "modelExtraction must default to empty in Java");
        assertEquals("", props.getLlm().getModelFormatting(), "modelFormatting must default to empty in Java");
        assertEquals("", props.getLlm().getModelOpus(), "modelOpus must default to empty in Java");
        assertEquals("", props.getLlm().getModelDrafting(), "modelDrafting must default to empty in Java");
        assertEquals("", props.getLlm().getModelTriage(), "modelTriage must default to empty in Java");
        assertEquals("", props.getLlm().getModelParsing(), "modelParsing must default to empty in Java");

        assertEquals("", props.getLlm().getAnthropic().getModel(), "anthropic.model must default to empty in Java");
        assertEquals("", props.getLlm().getOpenai().getModel(), "openai.model must default to empty in Java");
        assertEquals("", props.getLlm().getGemini().getModel(), "gemini.model must default to empty in Java");
        assertEquals("", props.getEmbedding().getModel(), "embedding.model must default to empty in Java");
    }
}
