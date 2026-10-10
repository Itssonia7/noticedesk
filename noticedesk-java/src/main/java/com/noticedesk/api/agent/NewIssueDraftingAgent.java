package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
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
public class NewIssueDraftingAgent {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ApiUsageLogService apiUsageLogService;
    private final LlmFactory llmFactory;

    public record NewIssueDraftingResult(
            int issueNo,
            String section04Html,
            String section05Html,
            String section06Html,
            List<RawAiCitation> citations,
            String strengthNote
    ) {}

    public record AiIssueSectionRecord(
            int issueNo,
            String model,
            String promptVersion,
            Map<String, Object> outputJson,
            String status
    ) {}

    public NewIssueDraftingResult generateNewIssueDraft(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            String fullOcrText,
            String stageName,
            UUID noticeId
    ) {
        String model = properties.getLlm() != null ? properties.getLlm().getModelNewIssue() : null;
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("LLM_MODEL_NEW_ISSUE is not configured. Set environment variable LLM_MODEL_NEW_ISSUE.");
        }

        String systemPrompt = loadPrompt();

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("issue", issue);
        userPayload.put("notice", noticeInfo);
        userPayload.put("full_ocr_text", fullOcrText);
        userPayload.put("stage", stageName);

        String userPrompt;
        try {
            userPrompt = objectMapper.writeValueAsString(userPayload);
        } catch (Exception e) {
            userPrompt = userPayload.toString();
        }

        String provider = properties.getLlm() != null ? properties.getLlm().getProviderPrimary() : "stub";
        if ("stub".equalsIgnoreCase(provider) || llmFactory == null) {
            log.info("NewIssueDraftingAgent running in stub mode for issue {}", issue.issueNo());
            return stubResult(issue);
        }

        try {
            LlmProvider llmProvider = llmFactory.getLlmForAgent("new_issue");
            LlmResponse response = llmProvider.generateText(systemPrompt, userPrompt, 6000, 0.0);
            if (apiUsageLogService != null) {
                apiUsageLogService.logUsage("anthropic", response.model() != null ? response.model() : model,
                        response.inputTokens() != null ? response.inputTokens() : 0,
                        response.outputTokens() != null ? response.outputTokens() : 0,
                        "new_issue", null, noticeId);
            }

            NewIssueDraftingResult parsed = parseAndSanitize(response.content(), issue.issueNo());
            if (parsed != null) {
                return parsed;
            }

            log.warn("NewIssueDraftingAgent JSON parse failed. Retrying once...");
            response = llmProvider.generateText(systemPrompt, userPrompt + "\n\nCRITICAL: Output valid JSON only.", 6000, 0.0);
            return parseAndSanitize(response.content(), issue.issueNo());
        } catch (Exception e) {
            log.error("NewIssueDraftingAgent execution failed for issue {}: {}", issue.issueNo(), e.getMessage());
            return stubResult(issue);
        }
    }

    private NewIssueDraftingResult parseAndSanitize(String json, int issueNo) {
        if (json == null || json.isBlank()) return null;
        try {
            String cleanJson = json.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "").trim();
            Map<String, Object> map = objectMapper.readValue(cleanJson, new TypeReference<>() {});

            String sec04 = HtmlSanitizer.sanitize(map.getOrDefault("section04_html", "").toString());
            String sec05 = HtmlSanitizer.sanitize(map.getOrDefault("section05_html", "").toString());
            String sec06 = HtmlSanitizer.sanitize(map.getOrDefault("section06_html", "").toString());
            String strengthNote = map.getOrDefault("strength_note", "Opus novel issue analysis completed.").toString();

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

            return new NewIssueDraftingResult(issueNo, sec04, sec05, sec06, citations, strengthNote);
        } catch (Exception e) {
            log.warn("Failed to parse NewIssueDraftingAgent JSON: {}", e.getMessage());
            return null;
        }
    }

    private NewIssueDraftingResult stubResult(MatchedIssue issue) {
        return new NewIssueDraftingResult(
                issue.issueNo(),
                "<p>Original grounds of defence for novel Issue #" + issue.issueNo() + ": It is submitted that the demand is based on misinterpretation of statutory provisions.</p>",
                "<li><strong>Para " + issue.issueNo() + ":</strong> Denied in full. Please refer to grounds for Issue #" + issue.issueNo() + ".</li>",
                "<p>Legal Submissions for Issue #" + issue.issueNo() + ": The revenue bears the initial burden of proof to establish liability.</p>",
                List.of(),
                "Strength Note: Novel issue written by Opus. High dependency on factual reconciliation."
        );
    }

    private String loadPrompt() {
        try {
            ClassPathResource res = new ClassPathResource("prompts/new_issue_v1.md");
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load prompts/new_issue_v1.md: {}", e.getMessage());
            return "You are an expert GST litigation advocate. Emits JSON {section04_html, section05_html, section06_html, citations:[], strength_note}. No invented facts.";
        }
    }
}
