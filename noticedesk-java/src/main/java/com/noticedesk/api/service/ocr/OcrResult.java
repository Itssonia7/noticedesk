package com.noticedesk.api.service.ocr;

/**
 * Text of one OCR run. {@code usage} is the token usage a model-based provider reports (null for the stub and for
 * providers that report none).
 */
public record OcrResult(String text, int pageCount, String providerName, OcrUsage usage) {

    public OcrResult(String text, int pageCount, String providerName) {
        this(text, pageCount, providerName, null);
    }

    /**
     * Token usage of a model-based OCR call. {@code tierInfo} names any response field that indicates the project /
     * tier the call was billed to (e.g. "trafficType=ON_DEMAND"); null when the response carries none.
     */
    public record OcrUsage(String provider, String model, Integer inputTokens, Integer outputTokens, String tierInfo) {
        public int inputTokensOrZero() {
            return inputTokens != null ? inputTokens : 0;
        }

        public int outputTokensOrZero() {
            return outputTokens != null ? outputTokens : 0;
        }
    }
}
