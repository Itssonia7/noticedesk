package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.llm.NoticeCallBudget;
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

    /** Issue kind (v3): a ground of demand or action stated by the department. */
    public static final String KIND_DEMAND = "demand";
    /** Issue kind (v3): a procedural / jurisdictional / limitation / form defect visible in the document itself. */
    public static final String KIND_DEFECT = "defect";

    /**
     * One matched issue. {@code cardIds} holds ONLY the primary card (one element, or empty for status
     * {@code none}), so template fill and every downstream consumer read it unchanged; the matcher's other
     * plausible cards are in {@code alternateCardIds} (at most two, never the primary). {@code kind} is
     * {@link #KIND_DEMAND} (default, every v1 / v2 issue) or {@link #KIND_DEFECT} (v3 visible-defect ground).
     * {@code clientFactsToConfirm} (v3.2) holds the primary card's CLIENT facts the notice does not evidence,
     * in the card's words, for the partner to confirm with the client; empty before v3.2 and for status none.
     */
    public record MatchedIssue(
            int issueNo,
            String status,
            String kind,
            List<String> cardIds,
            List<String> alternateCardIds,
            String why,
            List<String> notThisIfChecked,
            Map<String, Object> facts,
            List<String> noticeParas,
            List<String> flags,
            List<String> clientFactsToConfirm
    ) {
        public MatchedIssue {
            if (alternateCardIds == null) alternateCardIds = List.of();
            if (clientFactsToConfirm == null) clientFactsToConfirm = List.of();
            kind = normalizeKind(kind);
        }

        /** An issue with a kind and alternates but no client facts (the v3 / v3.1 shape). */
        public MatchedIssue(int issueNo, String status, String kind, List<String> cardIds, List<String> alternateCardIds, String why,
                            List<String> notThisIfChecked, Map<String, Object> facts, List<String> noticeParas, List<String> flags) {
            this(issueNo, status, kind, cardIds, alternateCardIds, why, notThisIfChecked, facts, noticeParas, flags, List.of());
        }

        /** An issue of kind {@code demand} with alternates (the v2 shape). */
        public MatchedIssue(int issueNo, String status, List<String> cardIds, List<String> alternateCardIds, String why,
                            List<String> notThisIfChecked, Map<String, Object> facts, List<String> noticeParas, List<String> flags) {
            this(issueNo, status, KIND_DEMAND, cardIds, alternateCardIds, why, notThisIfChecked, facts, noticeParas, flags);
        }

        /** An issue of kind {@code demand} without alternates (the v1 shape). */
        public MatchedIssue(int issueNo, String status, List<String> cardIds, String why, List<String> notThisIfChecked,
                            Map<String, Object> facts, List<String> noticeParas, List<String> flags) {
            this(issueNo, status, KIND_DEMAND, cardIds, List.of(), why, notThisIfChecked, facts, noticeParas, flags);
        }

        /** The primary card id, or null for an issue without one. */
        public String primaryCardId() {
            return cardIds == null || cardIds.isEmpty() ? null : cardIds.get(0);
        }

        /** True for a visible-defect ground (kind {@code defect}). */
        public boolean isDefect() {
            return KIND_DEFECT.equals(kind);
        }
    }

    /** {@code defect} for "defect" (any case, trimmed); everything else (null, blank, "demand", unknown) is {@code demand}. */
    static String normalizeKind(String kind) {
        return kind != null && KIND_DEFECT.equalsIgnoreCase(kind.trim()) ? KIND_DEFECT : KIND_DEMAND;
    }

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
        return matchNoticeWithOcrText(fullOcrText, noticeType, tenantId, noticeId, null);
    }

    /** Flag of a result whose call was refused by the notice's cost cap (no call was made for that attempt). */
    public static final String FLAG_COST_CAP = "matching_cost_cap";

    /**
     * Version of the live matching prompt (v3.3: a card's stage is tested against the RESPONSE the input document
     * calls for, per an input-document to response-stage table, never against the input document's own name;
     * v3.2: status decided on notice-visible DECISIVE facts only, the card's CLIENT facts returned as
     * {@code client_facts_to_confirm}, an untestable NOT THIS IF rule no longer lowers the status; v3.1: boundary
     * lines and {@code not_this_if_checked} written only for the chosen cards, {@code why} capped at 60 words of
     * prose, DIN/signature and proper-officer guards; v3 added visible-defect issues with {@code kind}, written
     * date-boundary checks and partial must cite the missing DECISIVE fact; v2 added one primary card + alternates
     * and grounds-first splitting).
     */
    public static final String PROMPT_VERSION = "issue_matching_v3_3";
    /** Prompt resource under {@code prompts/} ({@code issue_matching_v3.md} is edited in place for the v3.x versions). */
    public static final String PROMPT_FILE = "issue_matching_v3";

    /** Issue flag: the primary card id is not in the active catalogue (the issue is downgraded to {@code none}). */
    public static final String FLAG_UNKNOWN_CARD_ID = "unknown_card_id";
    /** Issue flag: status full / partial returned without a primary card (prompt self-check 3). */
    public static final String FLAG_HEDGED = "hedged";
    /** Issue flag (informational): a visible-defect ground (kind {@code defect}) that no catalogue card covers (status {@code none}). */
    public static final String FLAG_DEFECT_WITHOUT_CARD = "defect_without_card";
    /** Result flag: the model's {@code issues} count differs from its {@code grounds} count (prompt self-check 1). */
    public static final String FLAG_ISSUE_COUNT_MISMATCH = "issue_count_mismatch";
    /**
     * Issue flag (informational heuristic, v3.2): status partial and the {@code why} text contains the token
     * "CLIENT", so the model may have downgraded the match on a client-side fact, which the prompt forbids.
     * Never a failure; the status is kept.
     */
    public static final String FLAG_PARTIAL_ON_CLIENT_FACT = "partial_on_client_fact";
    /** Most alternates kept per issue; any beyond are dropped. */
    static final int MAX_ALTERNATES = 2;

    /**
     * As {@link #matchNoticeWithOcrText(String, String, UUID, UUID)}, checking every attempt (retry included)
     * against {@code budget} before the call and recording each billed call in it. A refused attempt ends
     * matching with a {@link #FLAG_COST_CAP} fallback result. A null budget checks nothing.
     */
    public MatchingResult matchNoticeWithOcrText(String fullOcrText, String noticeType, UUID tenantId, UUID noticeId,
                                                 NoticeCallBudget budget) {
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

        String effort = properties.getLlm().effortForAgent("matching");
        boolean raiseCap = false;
        int totalInTokens = 0;
        int totalOutTokens = 0;
        int totalCacheReadTokens = 0;
        int totalCacheWriteTokens = 0;

        LlmResponse response = null;
        JsonNode jsonNode = null;
        String validationError = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            String activeUserPrompt = userPrompt;
            if (attempt == 2 && validationError != null && !raiseCap) {
                activeUserPrompt += "\n\nCRITICAL FIX REQUIRED: Previous attempt failed validation with error: "
                        + validationError + ". Ensure you output STRICT JSON with all required fields.";
            }
            int maxTokens = properties.getLlm().maxTokensForAgent("matching", raiseCap);
            jsonNode = null;

            // hard cap: worst case of exactly this call (prompt and max_tokens as sent) must fit the budget;
            // the system prompt (rules + catalogue) is the cacheable prefix, the notice text the per-call part
            NoticeCallBudget.Prompt shape = NoticeCallBudget.Prompt.of(modelName, systemPrompt, activeUserPrompt);
            String refusal = budget != null ? budget.refusalReason("matching", shape, maxTokens) : null;
            if (refusal != null) {
                log.error("matching_not_called attempt={} noticeId={}: {}", attempt, noticeId, refusal);
                return createFallbackResult(FLAG_COST_CAP, refusal, catalogueHash, totalInTokens, totalOutTokens,
                        totalCacheReadTokens, totalCacheWriteTokens);
            }

            try {
                response = llm.generateWithEffort(systemPrompt, activeUserPrompt, modelName, maxTokens, effort, true);
            } catch (LlmFatalException e) {
                if (budget != null) budget.recordCall("matching", null, modelName);
                throw e;   // billing / auth error or live run aborted: no retry, no fallback result
            } catch (Exception e) {
                if (budget != null) budget.recordCall("matching", null, modelName);
                log.warn("matching_attempt_failed attempt={} error={}", attempt, e.getMessage());
                validationError = e.getMessage();
                outcome(attempt, false, e.getClass().getSimpleName());
                continue;
            }

            if (response == null) {
                validationError = "Model call returned no response";
                outcome(attempt, false, "no_response");
                continue;
            }

            // usage is billed whether or not the output is usable
            totalInTokens += response.inputTokensOrZero();
            totalOutTokens += response.outputTokensOrZero();
            totalCacheReadTokens += response.cacheReadTokensOrZero();
            totalCacheWriteTokens += response.cacheWriteTokensOrZero();
            if (budget != null) {
                budget.recordCall("matching", response, modelName, shape);
            } else if (apiUsageLogService != null && apiUsageLogService.promptCache() != null) {
                // no budget: still remember the catalogue's measured token count and that it is cached now
                int cacheTokens = response.cacheWriteTokensOrZero() + response.cacheReadTokensOrZero();
                if (cacheTokens > 0) apiUsageLogService.promptCache().recordUse(modelName, shape.prefixHash(), cacheTokens);
            }
            apiUsageLogService.logCall(response, modelName, "matching", attempt, tenantId, noticeId);

            if (response.refused()) {
                log.warn("matching_refused stop_reason={} attempt={}", response.stopReason(), attempt);
                validationError = "Model declined the request (stop_reason=refusal)";
                outcome(attempt, false, LlmResponse.FAILURE_REFUSAL);
                break;   // the same prompt will be refused again
            }
            if (response.truncated()) {
                log.warn("matching_attempt_truncated stop_reason=max_tokens attempt={} max_tokens={}", attempt, maxTokens);
                validationError = "Output was truncated due to max_tokens limit.";
                raiseCap = true;
                outcome(attempt, false, LlmResponse.FAILURE_MAX_TOKENS);
                continue;
            }
            if (response.failed()) {
                log.warn("matching_attempt_unusable failure={} stop_reason={} attempt={}",
                        response.failureReason(), response.stopReason(), attempt);
                validationError = "Model returned no usable text output (" + response.failureReason() + ")";
                outcome(attempt, false, response.failureReason());
                continue;
            }

            try {
                // fences / prose around the object are dropped; stray quotes inside strings are escaped
                jsonNode = AiOutputParser.readJsonTree(objectMapper, response.content());
            } catch (IllegalArgumentException e) {
                log.warn("matching_attempt_failed attempt={} error={}", attempt, e.getMessage());
                validationError = e.getMessage();
                outcome(attempt, false, "invalid_json");
                continue;
            }
            if (jsonNode.has("notice") && jsonNode.has("issues")) {
                validationError = null;
                outcome(attempt, true, null);
                break;
            }
            validationError = "JSON missing required 'notice' or 'issues' keys";
            outcome(attempt, false, "invalid_output");
        }

        if (jsonNode == null || validationError != null) {
            log.error("matching_failed_all_attempts noticeId={} error={}", noticeId, validationError);
            return createFallbackResult("matching_failed", validationError, catalogueHash, totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);
        }

        // Parse and validate issue cards against catalogue
        try {
            MatchedNoticeInfo noticeInfo = parseNoticeInfo(jsonNode.path("notice"));
            List<MatchedIssue> issues = new ArrayList<>();

            List<String> resultFlags = new ArrayList<>();
            JsonNode issuesArray = jsonNode.path("issues");
            if (issuesArray.isArray()) {
                for (JsonNode issueNode : issuesArray) {
                    int issueNo = issueNode.path("issue_no").asInt(issues.size() + 1);
                    String status = issueNode.path("status").asText("none");
                    String kind = normalizeKind(issueNode.path("kind").asText(null));   // v1 / v2 output: demand
                    String why = issueNode.path("why").asText("");

                    CardChoice choice = readCardChoice(issueNode);

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

                    List<String> clientFacts = readClientFactsToConfirm(issueNode);

                    List<String> issueFlags = new ArrayList<>();

                    // Unknown primary card -> the issue becomes "none" + flag unknown_card_id (as before)
                    List<String> primary = new ArrayList<>();
                    if (choice.primary() != null) {
                        if (validCardIds.contains(choice.primary())) {
                            primary.add(choice.primary());
                        } else {
                            log.warn("Issue #{}: Unknown card_id '{}' returned by LLM. Downgrading issue to 'none'.", issueNo, choice.primary());
                            status = "none";
                            issueFlags.add(FLAG_UNKNOWN_CARD_ID);
                        }
                    }

                    // Unknown alternates are dropped silently (logged); the primary never repeats as an alternate
                    List<String> alternates = new ArrayList<>();
                    for (String alt : choice.alternates()) {
                        if (alternates.size() >= MAX_ALTERNATES) break;
                        if (!validCardIds.contains(alt)) {
                            log.info("Issue #{}: unknown alternate card_id '{}' dropped", issueNo, alt);
                        } else if (!alt.equals(choice.primary()) && !alternates.contains(alt)) {
                            alternates.add(alt);
                        }
                    }

                    // Prompt self-check 3: a full / partial match must name a primary card
                    if (("full".equalsIgnoreCase(status) || "partial".equalsIgnoreCase(status)) && primary.isEmpty()) {
                        log.warn("Issue #{}: status '{}' without a primary card_id (hedged)", issueNo, status);
                        issueFlags.add(FLAG_HEDGED);
                    }

                    // Informational: a visible defect the catalogue has no card for (status none, after the unknown-card downgrade)
                    if (KIND_DEFECT.equals(kind) && "none".equalsIgnoreCase(status)) {
                        issueFlags.add(FLAG_DEFECT_WITHOUT_CARD);
                    }

                    // Informational heuristic (v3.2): a partial whose reason mentions a CLIENT fact
                    if (isPartialOnClientFact(status, why)) {
                        log.info("Issue #{}: partial match whose why mentions a CLIENT fact (informational)", issueNo);
                        issueFlags.add(FLAG_PARTIAL_ON_CLIENT_FACT);
                    }

                    issues.add(new MatchedIssue(issueNo, status, kind, primary, alternates, why, notThisIf, facts, noticeParas, issueFlags, clientFacts));
                }
            }

            // Prompt self-check 1: one issue per ground (only when the model returned the v2 grounds list)
            JsonNode groundsNode = jsonNode.path("grounds");
            if (groundsNode.isArray() && groundsNode.size() != issues.size()) {
                log.warn("matching_issue_count_mismatch grounds={} issues={} noticeId={}", groundsNode.size(), issues.size(), noticeId);
                resultFlags.add(FLAG_ISSUE_COUNT_MISMATCH);
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

            return new MatchingResult(noticeInfo, issues, paraMap, missingOrDoubtful, List.copyOf(resultFlags), catalogueHash,
                    totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);

        } catch (Exception e) {
            log.error("matching_parse_failed error={}", e.getMessage(), e);
            return createFallbackResult("matching_parse_failed", e.getMessage(), catalogueHash, totalInTokens, totalOutTokens, totalCacheReadTokens, totalCacheWriteTokens);
        }
    }

    /** The model's card choice for one issue before validation: primary (nullable) and alternates as returned. */
    record CardChoice(String primary, List<String> alternates) {}

    /**
     * Reads the card choice in either output shape: v2 {@code "card_id": "X", "alternates": [...]}, or
     * v1 {@code "card_ids": ["X", ...]} where the first id is the primary and the rest are alternates.
     * A blank or non-text {@code card_id} counts as null (no primary).
     */
    static CardChoice readCardChoice(JsonNode issueNode) {
        List<String> alternates = new ArrayList<>();
        if (issueNode.has("card_id") || issueNode.has("alternates")) {
            String primary = cleanId(issueNode.path("card_id"));
            JsonNode altNode = issueNode.path("alternates");
            if (altNode.isArray()) {
                for (JsonNode a : altNode) {
                    String id = cleanId(a);
                    if (id != null) alternates.add(id);
                }
            }
            return new CardChoice(primary, alternates);
        }
        JsonNode cardsNode = issueNode.path("card_ids");
        List<String> ids = new ArrayList<>();
        if (cardsNode.isArray()) {
            for (JsonNode c : cardsNode) {
                String id = cleanId(c);
                if (id != null) ids.add(id);
            }
        } else {
            String id = cleanId(cardsNode);
            if (id != null) ids.add(id);
        }
        String primary = ids.isEmpty() ? null : ids.get(0);
        if (ids.size() > 1) alternates.addAll(ids.subList(1, ids.size()));
        return new CardChoice(primary, alternates);
    }

    /**
     * The v3.2 {@code client_facts_to_confirm} list of one issue: its non-blank text entries, trimmed, in order.
     * Empty when the key is absent (v1 to v3.1 output), null, not an array, or holds no usable text.
     */
    static List<String> readClientFactsToConfirm(JsonNode issueNode) {
        JsonNode node = issueNode.path("client_facts_to_confirm");
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode f : node) {
            if (f == null || !f.isTextual()) continue;
            String s = f.asText().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }

    /** {@link #FLAG_PARTIAL_ON_CLIENT_FACT} heuristic: status partial and the why text contains the token "CLIENT" (upper case). */
    static boolean isPartialOnClientFact(String status, String why) {
        return "partial".equalsIgnoreCase(status) && why != null && why.contains("CLIENT");
    }

    private static String cleanId(JsonNode n) {
        if (n == null || !n.isTextual()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
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

    private void outcome(int attempt, boolean usable, String reason) {
        if (apiUsageLogService != null) apiUsageLogService.reportOutcome("matching", attempt, usable, reason);
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + PROMPT_FILE + ".md");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("Failed to load " + PROMPT_FILE + ".md prompt resource", e);
            return "Compare notice OCR text against issue cards and return JSON.";
        }
    }
}
