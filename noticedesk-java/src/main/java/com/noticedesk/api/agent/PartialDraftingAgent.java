package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.util.HtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartialDraftingAgent {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record PartialSectionAddition(
            int section,
            String afterBlock,
            String html
    ) {}

    public record RawAiCitation(
            String caseName,
            String court,
            Integer year,
            String quotedText,
            String citedFor
    ) {}

    public record PartialDraftingResult(
            List<PartialSectionAddition> sections,
            List<RawAiCitation> citations
    ) {}

    public PartialDraftingResult generatePartialDrafting(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            ReplyTemplate template,
            String stageName,
            UUID noticeId
    ) {
        String model = properties.getLlm() != null ? properties.getLlm().getModelPartialDrafting() : null;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("LLM_MODEL_PARTIAL_DRAFTING is not configured. Set environment variable LLM_MODEL_PARTIAL_DRAFTING.");
        }

        String promptTemplate = loadPrompt();
        String systemPrompt = promptTemplate;

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("issue", issue);
        userPayload.put("notice", noticeInfo);
        userPayload.put("template_id", template != null ? template.templateId() : null);
        userPayload.put("template_blocks", template != null ? template.blocks() : List.of());
        userPayload.put("stage", stageName);

        String userPrompt;
        try {
            userPrompt = objectMapper.writeValueAsString(userPayload);
        } catch (Exception e) {
            userPrompt = userPayload.toString();
        }

        // Check if using stub
        String provider = properties.getLlm() != null ? properties.getLlm().getProviderPrimary() : "stub";
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("PartialDraftingAgent running in stub mode for issue {}", issue.issueNo());
            return stubResult(issue, template);
        }

        // Live LLM Call
        try {
            LlmProvider llmProvider = llmFactory.getLlmForAgent("partial_drafting");
            LlmResponse response = llmProvider.generateText(systemPrompt, userPrompt, 4096, 0.0);
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                        response.inputTokens() != null ? response.inputTokens() : 0,
                        response.outputTokens() != null ? response.outputTokens() : 0,
                        "partial_drafting", null, noticeId);
            }

            String content = response.content();
            PartialDraftingResult parsed = parseAndSanitize(content);
            if (parsed != null) {
                return parsed;
            }

            // Retry 1: JSON parse / schema error
            log.warn("PartialDraftingAgent JSON parse failed on attempt 1. Retrying once...");
            response = llmProvider.generateText(systemPrompt, userPrompt + "\n\nCRITICAL: Output valid JSON only.", 4096, 0.0);
            return parseAndSanitize(response.content());
        } catch (Exception e) {
            log.error("PartialDraftingAgent execution failed for issue {}: {}", issue.issueNo(), e.getMessage());
            return stubResult(issue, template);
        }
    }

    private PartialDraftingResult parseAndSanitize(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            String cleanJson = json.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "").trim();
            Map<String, Object> map = objectMapper.readValue(cleanJson, new TypeReference<>() {});

            List<PartialSectionAddition> sections = new ArrayList<>();
            if (map.containsKey("sections") && map.get("sections") instanceof List<?> secList) {
                for (Object item : secList) {
                    if (item instanceof Map<?, ?> m) {
                        int sec = m.get("section") != null ? Integer.parseInt(m.get("section").toString()) : 4;
                        String after = m.get("after_block") != null ? m.get("after_block").toString() : null;
                        String rawHtml = m.get("html") != null ? m.get("html").toString() : "";
                        String sanitizedHtml = HtmlSanitizer.sanitize(rawHtml);
                        sections.add(new PartialSectionAddition(sec, after, sanitizedHtml));
                    }
                }
            }

            List<RawAiCitation> citations = new ArrayList<>();
            if (map.containsKey("citations") && map.get("citations") instanceof List<?> citList) {
                for (Object item : citList) {
                    if (item instanceof Map<?, ?> m) {
                        String cCase = m.get("case") != null ? m.get("case").toString() : null;
                        String cCourt = m.get("court") != null ? m.get("court").toString() : null;
                        Integer cYear = m.get("year") != null ? Integer.parseInt(m.get("year").toString()) : null;
                        String cQuote = m.get("quoted_text") != null ? m.get("quoted_text").toString() : null;
                        String cFor = m.get("cited_for") != null ? m.get("cited_for").toString() : null;
                        if (cCase != null) {
                            citations.add(new RawAiCitation(cCase, cCourt, cYear, cQuote, cFor));
                        }
                    }
                }
            }

            return new PartialDraftingResult(sections, citations);
        } catch (Exception e) {
            log.warn("Failed to parse PartialDraftingAgent JSON output: {}", e.getMessage());
            return null;
        }
    }

    private PartialDraftingResult stubResult(MatchedIssue issue, ReplyTemplate template) {
        String blockId = (template != null && !template.blocks().isEmpty()) ? template.blocks().get(0).id() : null;
        String html = "<p>Additional argument for Issue #" + issue.issueNo() + ": It is further submitted that statutory provisions must be interpreted in alignment with principles of natural justice.</p>";
        return new PartialDraftingResult(
                List.of(new PartialSectionAddition(4, blockId, html)),
                List.of()
        );
    }

    private String loadPrompt() {
        try {
            ClassPathResource res = new ClassPathResource("prompts/partial_drafting_v1.md");
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load prompts/partial_drafting_v1.md from classpath: {}", e.getMessage());
            return "You are an expert GST litigator. Emits JSON {sections:[], citations:[]}. No invented facts.";
        }
    }
}
