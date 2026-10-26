package com.noticedesk.api.service.ocr;

import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The gemini OCR provider's response handling, from canned response JSON (no network). */
class GeminiOcrProviderTest {

    private final GeminiOcrProvider provider = new GeminiOcrProvider(null, "test-ocr-model", 1000, 30);

    private static GenerateContentResponse response(String text, String extraUsage) {
        return GenerateContentResponse.fromJson("""
                {"candidates": [{"content": {"parts": [{"text": %s}]}, "finishReason": "STOP"}],
                 "usageMetadata": {"promptTokenCount": 2580, "candidatesTokenCount": 900, "thoughtsTokenCount": 100%s},
                 "modelVersion": "test-ocr-model"}
                """.formatted(com.fasterxml.jackson.databind.node.TextNode.valueOf(text).toString(), extraUsage));
    }

    @Test
    void textPagesAndUsageFromTheResponse() {
        OcrResult r = provider.toResult(response("=== PAGE 1 ===\nFORM GST DRC-01\n=== PAGE 2 ===\nPara 2\n=== PAGE 3 ===\nEnd", ""));
        assertTrue(r.text().contains("FORM GST DRC-01"));
        assertEquals(3, r.pageCount());
        assertEquals("gemini", r.providerName());
        assertEquals("test-ocr-model", r.usage().model());
        assertEquals(2580, r.usage().inputTokens());
        assertEquals(1000, r.usage().outputTokens(), "thinking tokens are billed as output");
        assertNull(r.usage().tierInfo(), "no tier / project field in the response");
    }

    @Test
    void tierFieldIsReportedWhenTheResponseCarriesOne() {
        assertEquals("usageMetadata.trafficType=ON_DEMAND",
                GeminiOcrProvider.tierInfo("{\"usageMetadata\": {\"promptTokenCount\": 1, \"trafficType\": \"ON_DEMAND\"},"
                        + " \"candidates\": [{\"content\": {\"parts\": [{\"text\": \"project tier\"}]}}]}"));
        assertNull(GeminiOcrProvider.tierInfo("{\"modelVersion\": \"m\", \"usageMetadata\": {\"promptTokenCount\": 1}}"));
        assertNull(GeminiOcrProvider.tierInfo("not json"));
    }

    @Test
    void noTextIsAnOcrErrorAndNoKeyRefusesTheCall() {
        GenerateContentResponse empty = GenerateContentResponse.fromJson(
                "{\"candidates\": [{\"finishReason\": \"SAFETY\"}], \"usageMetadata\": {\"promptTokenCount\": 5}}");
        OcrException e = assertThrows(OcrException.class, () -> provider.toResult(empty));
        assertTrue(e.getMessage().contains("SAFETY"));
        assertThrows(OcrException.class, () -> provider.process(new byte[]{1}, "a.pdf", "application/pdf"));
        assertEquals(1, GeminiOcrProvider.pageCount("no markers"));
    }
}
