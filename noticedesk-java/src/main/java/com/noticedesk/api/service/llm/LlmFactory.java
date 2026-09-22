package com.noticedesk.api.service.llm;

import com.noticedesk.api.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches {@link LlmProvider} instances keyed by
 * {@code "providerName:model"}.
 *
 * <p>Use {@link #getLlmForAgent(String)} (preferred) to get the provider for a
 * named agent — it applies per-agent model overrides from
 * {@code noticedesk.llm.model.*} so cost-tier splits (Opus for drafting,
 * Sonnet for parsing/triage) happen automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmFactory {

    private final AppProperties properties;

    /** Cache key: "providerName:model" */
    private final Map<String, LlmProvider> cache = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the primary LLM provider with its model resolved for {@code agentName}.
     *
     * @param agentName  one of {@code "drafting"}, {@code "triage"}, {@code "parsing"}
     */
    public LlmProvider getLlmForAgent(String agentName) {
        String providerName = properties.getLlm().getProviderPrimary();
        String model = properties.modelForAgent(agentName, providerName);
        return getOrCreate(providerName, model);
    }

    /**
     * Returns a dedicated Claude Opus provider for Case 4 novel notice deep drafting.
     */
    public LlmProvider getOpusProvider() {
        String apiKey = properties.getLlm().getAnthropic().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            return getOrCreate("anthropic", "claude-3-opus-20240229");
        }
        return getLlmForAgent("drafting");
    }

    /**
     * Returns the secondary LLM provider, or empty if none is configured or it
     * equals the primary.
     */
    public Optional<LlmProvider> getSecondaryLlmForAgent(String agentName) {
        AppProperties.Llm llm = properties.getLlm();
        String secondary = llm.getProviderSecondary();
        if (secondary == null || secondary.isBlank()
                || secondary.equals(llm.getProviderPrimary())) {
            return Optional.empty();
        }
        String model = properties.modelForAgent(agentName, secondary);
        return Optional.of(getOrCreate(secondary, model));
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private LlmProvider getOrCreate(String providerName, String model) {
        String key = providerName + ":" + model;
        return cache.computeIfAbsent(key, k -> {
            LlmProvider p = build(providerName, model);
            log.info("llm_provider_created provider={} model={}", p.getName(), p.getModel());
            return p;
        });
    }

    private LlmProvider build(String providerName, String model) {
        String name = providerName != null ? providerName : "stub";
        return switch (name) {
            case "anthropic" -> {
                AppProperties.Llm.Anthropic cfg = properties.getLlm().getAnthropic();
                if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
                    throw new LlmException(
                            "noticedesk.llm.anthropic.api-key must be set for anthropic provider");
                }
                String resolvedModel = (model != null && !model.isBlank()) ? model : cfg.getModel();
                yield new AnthropicLlmProvider(cfg.getApiKey(), resolvedModel, cfg.getTimeoutSeconds());
            }
            case "openai" -> {
                AppProperties.Llm.OpenAi cfg = properties.getLlm().getOpenai();
                if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
                    throw new LlmException(
                            "noticedesk.llm.openai.api-key must be set for openai provider");
                }
                String resolvedModel = (model != null && !model.isBlank()) ? model : cfg.getModel();
                yield new OpenAiLlmProvider(cfg.getApiKey(), resolvedModel,
                        cfg.getTimeoutSeconds(), cfg.getBaseUrl());
            }
            case "gemini" -> {
                AppProperties.Llm.Gemini cfg = properties.getLlm().getGemini();
                String resolvedModel = (model != null && !model.isBlank()) ? model : cfg.getModel();
                yield new GeminiLlmProvider(cfg.getApiKey(), resolvedModel, cfg.getTimeoutSeconds());
            }
            case "stub" -> new StubLlmProvider(properties.getLlm().getStubDefaultCanned());
            default -> throw new LlmException(
                    "Unknown LLM provider: '" + name + "' (valid: anthropic, openai, gemini, stub)");
        };
    }
}
