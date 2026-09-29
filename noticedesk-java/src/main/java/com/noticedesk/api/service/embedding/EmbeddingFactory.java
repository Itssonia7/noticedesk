package com.noticedesk.api.service.embedding;

import com.noticedesk.api.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class EmbeddingFactory {

    private final AppProperties properties;

    @Bean
    @Primary
    public EmbeddingProvider embeddingProvider() {
        String provider = properties.getEmbedding().getProvider();
        log.info("embedding_provider_created provider={}", provider);

        if ("gemini".equalsIgnoreCase(provider)) {
            String apiKey = properties.getLlm().getGemini().getApiKey();
            String model = properties.getEmbedding().getModel();
            int dim = properties.getEmbedding().getOutputDimensionality();
            return new GeminiEmbeddingProvider(apiKey, model, dim);
        }

        return new StubEmbeddingProvider();
    }
}
