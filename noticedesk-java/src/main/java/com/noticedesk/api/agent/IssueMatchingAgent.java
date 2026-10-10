package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.matching.CatalogueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class IssueMatchingAgent {

    private final LlmFactory llmFactory;
    private final CatalogueService catalogueService;
    private final ApiUsageLogService apiUsageLogService;
    private final AppProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record MatchedNoticeInfo(
            String noticeNumber,
            String din,
            String issueDate,
            String replyDueDate,
            String financialYear,
            String taxPeriod,
            Double totalDemandAmount,
            String authority
    ) {}

    public record MatchedIssue(
            int issueNo,
            String status,
            List<String> cardIds,
            String why,
            List<String> notThisIfChecked,
            Map<String, Object> facts,
            List<String> noticeParas,
            List<String> flags
    ) {}

    public record MatchingResult(
            MatchedNoticeInfo notice,
            List<MatchedIssue> issues,
            Map<String, List<Integer>> paraMap,
            List<String> missingOrDoubtful,
            List<String> flags,
            String catalogueHash,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheWriteTokens
    ) {}

    /**
     * Loads the UNTRUNCATED full OCR text of the notice document directly from the database
     * (table documents_inbox.ocr_text linked via notice_id -> source_inbox_id, or documents.extracted_text).
     */
    public String loadFullOcrTextFromDb(UUID noticeId) {
        if (jdbc == null || noticeId == null) {
            return "";
        }
        String sql = """
                SELECT COALESCE(ib.ocr_text, doc.extracted_text, '') AS full_text
                FROM notices n
                LEFT JOIN documents_inbox ib ON ib.inbox_id = n.source_inbox_id
                LEFT JOIN documents doc ON doc.matter_id = n.matter_id
                WHERE n.notice_id = CAST(:nid AS UUID)
                LIMIT 1
                """;
        List<String> rows = jdbc.query(sql, Map.of("nid", noticeId.toString()), (rs, rowNum) -> rs.getString("full_text"));
        return rows.isEmpty() ? "" : rows.get(0);
    }

    /**
     * Runs issue matching using Anthropic Sonnet (or configured LLM_MODEL_MATCHING model).
     */
    public MatchingResult matchNotice(UUID noticeId, String noticeType, UUID tenantId) {
        String fullOcrText = loadFullOcrTextFromDb(noticeId);
        return matchNoticeWithOcrText(fullOcrText, noticeType, tenantId, noticeId);
    }

    public MatchingResult matchNoticeWithOcrText(String fullOcrText, String noticeType, UUID tenantId, UUID noticeId) {
        List<IssueCard> cards = catalogueService.getActiveCards();
        String compactCatalogueText = catalogueService.renderCatalogueCompactText(cards);
        String catalogueHash = catalogueService.getCatalogueHash(compactCatalogueText);

        Set<String> validCardIds = new HashSet<>();
        for (IssueCard card : cards) {
            validCardIds.add(card.cardId());
        }

        LlmProvider llm = llmFactory.getLlmForAgent("matching");
        String modelName = properties.getLlm().getModelMatching();

        String promptTemplate = loadPromptTemplate();
        String systemPrompt = promptTemplate + "\n\n" + compactCatalogueText;
        String userPrompt = "NOTICE TYPE FROM TRIAGE: " + noticeType + "\n\nFULL NOTICE OCR TEXT:\n" + fullOcrText;

        int maxTokens = 4096;
        int totalInTokens = 0;
        int totalOutTokens = 0;
        int totalCacheReadTokens = 0;
        int totalCacheWriteTokens = 0;

        LlmResponse response = null;
        JsonNode jsonNode = null;
        String validationError = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            String activeUserPrompt = userPrompt;
            if (attempt == 2 && validationError != null) {
                activeUserPrompt += "\n\nCRITICAL FIX REQUIRED: Previous attempt failed validation with error: "
                        + validationError + ". Ensure you output STRICT JSON with all required fields.";
                maxTokens = 8192; // Increase max_tokens on retry per requirement 8
            }

            try {
                response = llm.generateWithPromptCaching(systemPrompt, activeUserPrompt, modelName, maxTokens, 0.0, true);

                totalInTokens += (response.inputTokens() != null ? response.inputTokens() : 0);
                totalOutTokens += (response.outputTokens() != null ? response.outputTokens() : 0);
                totalCacheReadTokens += (response.cacheReadInputTokens() != null ? response.cacheReadInputTokens() : 0);
                totalCacheWriteTokens += (response.cacheCreationInputTokens() != null ? response.cacheCreationInputTokens() : 0);

                apiUsageLogService.logUsage("anthropic", response.model(),
                        response.inputTokens() != null ? response.inputTokens() : 0,
                        response.outputTokens() != null ? response.outputTokens() : 0,
                        response.cacheReadInputTokens() != null ? response.cacheReadInputTokens() : 0,
                        response.cacheCreationInputTokens() != null ? response.cacheCreationInputTokens() : 0,
                        "matching", tenantId, noticeId);

                // Requirement 8: If stop_reason is max_tokens, treat as failed attempt and retry
                if ("max_tokens".equalsIgnoreCase(response.stopReason())) {
                    log.warn("matching_attempt_truncated stop_reason=max_tokens attempt={}", attempt);
                    validationError = "Output was truncated due to max_tokens limit.";
                    continue;
                }

                String cleanContent = sanitizeJsonContent(response.content());
                jsonNode = objectMapper.readTree(cleanContent);

                if (jsonNode.has("notice") && jsonNode.has("issues")) {
                    validationError = null;
                    break;
                } else {
                    validationError = "JSON missing required 'notice' or 'issues' keys";
                }

            } catch (Exception e) {
                log.warn("matching_attempt_failed attempt={} error={}", attempt, e.getMessage());
                validationError = e.getMessage();
            }
        }

        if (jsonNode == null || validationError != null) {
            log.error("matching_failed_all_attempts noticeId={} error={}", noticeId, validationError);
            return createFallbackResult("matching_failed", validationError, catalogueHash, totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);
        }

        // Parse and validate issue cards against catalogue
        try {
            MatchedNoticeInfo noticeInfo = parseNoticeInfo(jsonNode.path("notice"));
            List<MatchedIssue> issues = new ArrayList<>();

            JsonNode issuesArray = jsonNode.path("issues");
            if (issuesArray.isArray()) {
                for (JsonNode issueNode : issuesArray) {
                    int issueNo = issueNode.path("issue_no").asInt(issues.size() + 1);
                    String status = issueNode.path("status").asText("none");
                    String why = issueNode.path("why").asText("");

                    List<String> cardIds = new ArrayList<>();
                    JsonNode cardsNode = issueNode.path("card_ids");
                    if (cardsNode.isArray()) {
                        for (JsonNode c : cardsNode) {
                            cardIds.add(c.asText());
                        }
                    }

                    List<String> notThisIf = new ArrayList<>();
                    JsonNode notNode = issueNode.path("not_this_if_checked");
                    if (notNode.isArray()) {
                        for (JsonNode n : notNode) {
                            notThisIf.add(n.asText());
                        }
                    }

                    List<String> noticeParas = new ArrayList<>();
                    JsonNode parasNode = issueNode.path("notice_paras");
                    if (parasNode.isArray()) {
                        for (JsonNode p : parasNode) {
                            noticeParas.add(p.asText());
                        }
                    }

                    Map<String, Object> facts = objectMapper.convertValue(issueNode.path("facts"), new TypeReference<Map<String, Object>>() {});
                    if (facts == null) facts = Map.of();

                    List<String> issueFlags = new ArrayList<>();

                    // Requirement: Unknown card_id returned -> that issue becomes "none" + flag "unknown_card_id"
                    List<String> validCardsForIssue = new ArrayList<>();
                    boolean hasUnknownCard = false;
                    for (String cid : cardIds) {
                        if (validCardIds.contains(cid)) {
                            validCardsForIssue.add(cid);
                        } else {
                            hasUnknownCard = true;
                            log.warn("Issue #{}: Unknown card_id '{}' returned by LLM. Downgrading issue to 'none'.", issueNo, cid);
                        }
                    }

                    if (hasUnknownCard) {
                        status = "none";
                        issueFlags.add("unknown_card_id");
                    }

                    issues.add(new MatchedIssue(issueNo, status, validCardsForIssue, why, notThisIf, facts, noticeParas, issueFlags));
                }
            }

            Map<String, List<Integer>> paraMap = objectMapper.convertValue(jsonNode.path("para_map"), new TypeReference<Map<String, List<Integer>>>() {});
            if (paraMap == null) paraMap = Map.of();

            List<String> missingOrDoubtful = new ArrayList<>();
            JsonNode missingNode = jsonNode.path("missing_or_doubtful");
            if (missingNode.isArray()) {
                for (JsonNode m : missingNode) {
                    missingOrDoubtful.add(m.asText());
                }
            }

            return new MatchingResult(noticeInfo, issues, paraMap, missingOrDoubtful, List.of(), catalogueHash,
                    totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);

        } catch (Exception e) {
            log.error("matching_parse_failed error={}", e.getMessage(), e);
            return createFallbackResult("matching_parse_failed", e.getMessage(), catalogueHash, totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);
        }
    }

    private MatchingResult createFallbackResult(String flag, String errorDetail, String hash, int in, int out, int cRead, int cWrite) {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo(null, null, null, null, null, null, null, null);
        String whyMsg = errorDetail != null ? "Matching failed: " + errorDetail : "Matching failed or returned invalid output.";
        MatchedIssue fallbackIssue = new MatchedIssue(1, "none", List.of(), whyMsg,
                List.of(), Map.of(), List.of(), List.of(flag));
        List<String> missing = errorDetail != null ? List.of(flag, errorDetail) : List.of(flag);
        return new MatchingResult(noticeInfo, List.of(fallbackIssue), Map.of(), missing,
                List.of(flag), hash, in, out, cRead, cWrite);
    }

    private MatchedNoticeInfo parseNoticeInfo(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return new MatchedNoticeInfo(null, null, null, null, null, null, null, null);
        }
        Double demand = n.hasNonNull("total_demand_amount") ? n.path("total_demand_amount").asDouble() : null;
        return new MatchedNoticeInfo(
                n.path("notice_number").asText(null),
                n.path("din").asText(null),
                n.path("issue_date").asText(null),
                n.path("reply_due_date").asText(null),
                n.path("financial_year").asText(null),
                n.path("tax_period").asText(null),
                demand,
                n.path("authority").asText(null)
        );
    }

    private String sanitizeJsonContent(String content) {
        if (content == null) return "{}";
        String s = content.strip();
        if (s.startsWith("```json")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/issue_matching_v1.md");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("Failed to load issue_matching_v1.md prompt resource", e);
            return "Compare notice OCR text against issue cards and return JSON.";
        }
    }
}
