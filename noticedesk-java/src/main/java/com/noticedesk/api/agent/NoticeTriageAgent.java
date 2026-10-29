package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triage agent: reads a parsed notice and produces a partner-facing summary
 * plus a document checklist of evidence to gather before drafting.
 *
 * <p>Calls the primary LLM (falls back to the stub in dev/test).
 * The stub returns a pre-canned triage response so the whole triage
 * pipeline exercises every downstream code path without an LLM key.
 *
 * <p><strong>TODO (Sprint N):</strong> load the full triage prompt template
 * from {@code prompts/notice_triage_v1.md}, render with notice context, and
 * validate the response schema strictly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeTriageAgent {

    private final LlmFactory llmFactory;
    private final ObjectMapper objectMapper;

    /**
     * Run triage for a notice.
     *
     * @param noticeData flat map from the notice/matter/client JOIN (as loaded
     *                   by {@code TriageController})
     * @return map with {@code summary} (String), {@code risk_level} (String),
     *         {@code checklist} (List&lt;Map&gt;) — each item has
     *         {@code name}, {@code description}, {@code required}
     */
    public Map<String, Object> generateTriage(Map<String, Object> noticeData) {
        log.info("triage_agent notice_id={}", noticeData.get("notice_id"));

        String systemPrompt = loadSystemPrompt();
        String userPrompt   = buildUserPrompt(noticeData);

        // Force the triage step to ALWAYS use Gemini, ignoring the primary provider config.
        LlmProvider llm = llmFactory.getGeminiProviderForAgent("triage");
        LlmResponse resp = llm.generateText(systemPrompt, userPrompt, 4000, 0.2);

        return parseAndRemap(resp.content(), noticeData);
    }

    // -----------------------------------------------------------------------

    private Map<String, Object> parseAndRemap(String content, Map<String, Object> noticeData) {
        try {
            String text = content.strip();
            if (text.startsWith("```")) {
                int firstNl = text.indexOf('\n');
                if (firstNl != -1) text = text.substring(firstNl + 1);
                if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
                text = text.strip();
            }
            Map<String, Object> parsed = objectMapper.readValue(text,
                    new TypeReference<Map<String, Object>>() {});

            // Remap checklist items from LLM format → controller format
            // LLM:        {label, rationale, doc_type, is_required}
            // Controller: {name,  description,          required}
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawChecklist =
                    (List<Map<String, Object>>) parsed.getOrDefault("checklist", List.of());

            List<Map<String, Object>> checklist = new ArrayList<>();
            for (Map<String, Object> item : rawChecklist) {
                Map<String, Object> mapped = new HashMap<>();
                mapped.put("name",        item.getOrDefault("label",      item.getOrDefault("name", "")));
                mapped.put("description", item.getOrDefault("rationale",  item.getOrDefault("description", "")));
                mapped.put("required",    item.getOrDefault("is_required", item.getOrDefault("required", false)));
                checklist.add(mapped);
            }

            String summary   = (String) parsed.getOrDefault("summary", "Triage analysis complete.");
            String riskLevel = (String) parsed.getOrDefault("risk_level", "medium");

            return Map.of("summary", summary, "risk_level", riskLevel, "checklist", checklist);

        } catch (Exception e) {
            log.warn("triage_parse_failed notice_id={} error={} — returning stub result",
                    noticeData.get("notice_id"), e.getMessage());
            return fallbackResult(noticeData);
        }
    }

    private static Map<String, Object> fallbackResult(Map<String, Object> noticeData) {
        String docType = String.valueOf(noticeData.getOrDefault("document_type", "notice"));
        return Map.of(
                "summary",    "Received a " + docType + ". Please review and respond.",
                "risk_level", "medium",
                "checklist",  List.of(
                        Map.of("name", "Copy of Notice", "description",
                               "The original notice is required for filing a reply.",
                               "required", true)));
    }

    private String loadSystemPrompt() {
        try {
            ClassPathResource res = new ClassPathResource("prompts/notice_triage_v1.md");
            if (res.exists()) {
                String md = res.getContentAsString(StandardCharsets.UTF_8);
                String[] parts = md.split("## System prompt", 2);
                if (parts.length > 1) {
                    return extractFenced(parts[1]);
                }
            }
        } catch (Exception e) {
            log.warn("triage_prompt_load_failed: {}", e.getMessage());
        }
        return "You are the NoticeDesk triage agent. Analyse the tax notice and return strict JSON with summary, risk_level, and checklist.";
    }

    private String buildUserPrompt(Map<String, Object> noticeData) {
        // Minimal user prompt — the full template rendering is a TODO for the next sprint.
        return "Notice context:\n" + noticeData;
    }

    private static String extractFenced(String s) {
        int start = s.indexOf("```");
        if (start == -1) return s;
        int nl = s.indexOf('\n', start);
        if (nl == -1) return s;
        int end = s.indexOf("```", nl + 1);
        if (end == -1) return s;
        return s.substring(nl + 1, end).strip();
    }
}
