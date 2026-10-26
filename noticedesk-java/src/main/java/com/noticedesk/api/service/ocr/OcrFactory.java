package com.noticedesk.api.service.ocr;

import com.noticedesk.api.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrFactory {

    private final AppProperties properties;

    private volatile OcrProvider primaryProvider;

    public OcrProvider getPrimaryProvider() {
        if (primaryProvider == null) {
            synchronized (this) {
                if (primaryProvider == null) {
                    primaryProvider = buildProvider(properties.getOcr().getProviderPrimary());
                    log.info("ocr_primary_provider={}", primaryProvider.getName());
                }
            }
        }
        return primaryProvider;
    }

    public Optional<OcrProvider> getFallbackProvider() {
        String fallback = properties.getOcr().getProviderFallback();
        if (fallback == null || fallback.isBlank()) return Optional.empty();
        return Optional.of(buildProvider(fallback));
    }

    private OcrProvider buildProvider(String name) {
        return switch (name != null ? name : "stub") {
            case "stub" -> new StubOcrProvider();
            case "gemini" -> {
                AppProperties.Ocr.Gemini g = properties.getOcr().getGemini();
                String model = g.getModel() != null && !g.getModel().isBlank() ? g.getModel() : properties.getLlm().getGemini().getModel();
                yield new GeminiOcrProvider(properties.getLlm().getGemini().getApiKey(), model, g.getMaxOutputTokens(), g.getTimeoutSeconds());
            }
            case "google_doc_ai" -> {
                // TODO: wire GoogleDocumentAiOcrProvider with credentials from
                //   noticedesk.ocr.google-doc-ai.* when that SDK is added.
                log.warn("ocr_provider=google_doc_ai not yet implemented — falling back to stub");
                yield new StubOcrProvider();
            }
            case "azure_doc_intel" -> {
                // TODO: wire AzureDocumentIntelligenceOcrProvider with credentials from
                //   noticedesk.ocr.azure-doc-intel.* when that SDK is added.
                log.warn("ocr_provider=azure_doc_intel not yet implemented — falling back to stub");
                yield new StubOcrProvider();
            }
            default -> {
                log.warn("ocr_unknown_provider={} — falling back to stub", name);
                yield new StubOcrProvider();
            }
        };
    }
}
