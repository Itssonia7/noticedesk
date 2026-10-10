package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.SourceMapEntry;
import com.noticedesk.api.model.matching.TemplateBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateFillService {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    // In-memory template registry for unit tests or fallback when DB is absent
    private final Map<String, List<ReplyTemplate>> inMemoryTemplates = new HashMap<>();

    public void registerInMemoryTemplate(ReplyTemplate template) {
        inMemoryTemplates.computeIfAbsent(template.cardId(), k -> new ArrayList<>()).add(template);
    }

    public record FilledBlock(
            String blockId,
            int section,
            String filledHtml,
            List<String> citations,
            List<String> missingVars,
            List<String> flags
    ) {}

    public record FilledTemplateResult(
            String templateId,
            int templateVersion,
            String cardId,
            String summaryLine,
            List<FilledBlock> blocks,
            List<SourceMapEntry> sourceMapEntries,
            List<String> missingVars,
            List<String> flags,
            List<String> documents,
            List<String> proceduralObjectionOptions,
            boolean periodMissing
    ) {}

    /**
     * Fills an issue template for a matched issue.
     * Uses the ISSUE's tax period from matching facts (facts.get("period")), NOT the notice date.
     */
    public FilledTemplateResult fillTemplateForIssue(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            DraftingAgent.DraftingInput input
    ) {
        Map<String, Object> facts = issue.facts() != null ? issue.facts() : Map.of();
        Object periodObj = facts.get("period");
        String period = periodObj != null ? periodObj.toString().trim() : null;

        // Rule 5: If issue period is missing: [[MISSING: period]] + block flag. No fallback to notice date.
        if (period == null || period.isBlank()) {
            log.warn("Issue #{} missing tax period in facts. Marking [[MISSING: period]]", issue.issueNo());
            return new FilledTemplateResult(
                    "MISSING_PERIOD", 1, getCardId(issue),
                    "[[MISSING: period]]",
                    List.of(), List.of(),
                    List.of("period"), List.of("missing_period"),
                    List.of(), List.of(), true
            );
        }

        String cardId = getCardId(issue);
        if (cardId == null) {
            return createNoTemplateResult(cardId, "No card_id provided for issue #" + issue.issueNo());
        }

        ReplyTemplate template = selectTemplateForCardAndPeriod(cardId, period);
        if (template == null) {
            log.warn("No active template found for card_id={} period={}", cardId, period);
            return createNoTemplateResult(cardId, "no_template_available");
        }

        Map<String, Object> contextMap = buildContextMap(issue, noticeInfo, input);
        List<String> declaredVars = template.variables() != null ? template.variables() : List.of();

        List<FilledBlock> filledBlocks = new ArrayList<>();
        List<SourceMapEntry> sourceMapEntries = new ArrayList<>();
        List<String> allMissingVars = new ArrayList<>();
        List<String> allFlags = new ArrayList<>();

        int paraCounter = 1;

        for (TemplateBlock block : template.blocks()) {
            // Evaluate conditional "when" if present
            if (block.when() != null && !block.when().isBlank()) {
                if (!evaluateWhenCondition(block.when(), contextMap)) {
                    log.debug("Skipping block {} due to when condition false: {}", block.id(), block.when());
                    continue;
                }
            }

            BlockFillResult fillRes = fillBlockHtml(block, declaredVars, contextMap, template.templateId());
            filledBlocks.add(new FilledBlock(
                    block.id(), block.section(), fillRes.html(),
                    block.citations() != null ? block.citations() : List.of(),
                    fillRes.missingVars(), fillRes.flags()
            ));

            allMissingVars.addAll(fillRes.missingVars());
            allFlags.addAll(fillRes.flags());

            sourceMapEntries.add(new SourceMapEntry(
                    paraCounter++, block.section(), template.templateId(), template.version(), block.id()
            ));
        }

        // Substitute summary line
        BlockFillResult summaryRes = fillTextPlaceholders(
                template.summaryLine() != null ? template.summaryLine() : "",
                declaredVars, contextMap, template.templateId()
        );
        allMissingVars.addAll(summaryRes.missingVars());
        allFlags.addAll(summaryRes.flags());

        return new FilledTemplateResult(
                template.templateId(), template.version(), cardId,
                summaryRes.html(), filledBlocks, sourceMapEntries,
                allMissingVars, allFlags,
                template.documents() != null ? template.documents() : List.of(),
                template.proceduralObjectionOptions() != null ? template.proceduralObjectionOptions() : List.of(),
                false
        );
    }

    private String getCardId(MatchedIssue issue) {
        if (issue.cardIds() != null && !issue.cardIds().isEmpty()) {
            return issue.cardIds().get(0);
        }
        return null;
    }

    private FilledTemplateResult createNoTemplateResult(String cardId, String reason) {
        return new FilledTemplateResult(
                "NONE", 0, cardId,
                null,  // internal reason goes to Section 13 via the flag, never to the client-facing summary
                List.of(), List.of(),
                List.of(), List.of("no_template_available"),
                List.of(), List.of(), false
        );
    }

    /**
     * Selects active/draft template for card matching the issue period.
     */

    public ReplyTemplate selectTemplateForCardAndPeriod(String cardId, String period) {
        boolean allowDraft = properties == null || properties.getMatching() == null || properties.getMatching().isAllowDraftCards();

        List<ReplyTemplate> candidates = loadTemplatesForCard(cardId, allowDraft);
        if (candidates.isEmpty()) {
            return null;
        }

        // Filter by effective date matching the period
        for (ReplyTemplate t : candidates) {
            if (isPeriodWithinEffectiveDates(period, t.effectiveFrom(), t.effectiveTo())) {
                return t;
            }
        }
        return null;
    }

    private List<ReplyTemplate> loadTemplatesForCard(String cardId, boolean allowDraft) {
        List<ReplyTemplate> list = new ArrayList<>();

        if (inMemoryTemplates.containsKey(cardId)) {
            for (ReplyTemplate t : inMemoryTemplates.get(cardId)) {
                if ("active".equalsIgnoreCase(t.status()) || (allowDraft && "draft".equalsIgnoreCase(t.status()))) {
                    list.add(t);
                }
            }
        }

        if (jdbcTemplate != null) {
            String sql = """
                    SELECT template_id, version, card_id, effective_from, effective_to, status,
                           blocks, variables, documents, summary_line, procedural_objection_options,
                           approved_by, approved_at, created_at
                    FROM reply_templates
                    WHERE card_id = ? AND status IN (%s)
                    ORDER BY version DESC
                    """.formatted(allowDraft ? "'active', 'draft'" : "'active'");
            try {
                List<ReplyTemplate> dbTemplates = jdbcTemplate.query(sql, (rs, rowNum) -> {
                    String tId = rs.getString("template_id");
                    int ver = rs.getInt("version");
                    String cId = rs.getString("card_id");
                    LocalDate effFrom = rs.getDate("effective_from") != null ? rs.getDate("effective_from").toLocalDate() : null;
                    LocalDate effTo = rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null;
                    String st = rs.getString("status");
                    List<TemplateBlock> blocks = parseBlocksJson(rs.getString("blocks"));
                    List<String> vars = parseJsonList(rs.getString("variables"));
                    List<String> docs = parseJsonList(rs.getString("documents"));
                    String sumLine = rs.getString("summary_line");
                    List<String> procOptions = parseJsonList(rs.getString("procedural_objection_options"));
                    String appBy = rs.getString("approved_by");
                    OffsetDateTime appAt = rs.getObject("approved_at", OffsetDateTime.class);
                    OffsetDateTime crAt = rs.getObject("created_at", OffsetDateTime.class);

                    return new ReplyTemplate(tId, ver, cId, effFrom, effTo, st, blocks, vars, docs, sumLine, procOptions, appBy, appAt, crAt);
                }, cardId);
                list.addAll(dbTemplates);
            } catch (Exception e) {
                log.warn("DB query for reply_templates failed or table missing: {}", e.getMessage());
            }
        }

        list.sort(Comparator.comparingInt(ReplyTemplate::version).reversed());
        return list;
    }

    private boolean isPeriodWithinEffectiveDates(String period, LocalDate effFrom, LocalDate effTo) {
        if (effFrom == null && effTo == null) {
            return true;
        }
        // Basic year extraction from period e.g. "2022-23" -> 2022-04-01 to 2023-03-31
        LocalDate periodStart = parsePeriodStart(period);
        LocalDate periodEnd = parsePeriodEnd(period);

        if (effFrom != null && periodEnd.isBefore(effFrom)) {
            return false;
        }
        if (effTo != null && periodStart.isAfter(effTo)) {
            return false;
        }
        return true;
    }

    private LocalDate parsePeriodStart(String period) {
        if (period == null) return LocalDate.of(2017, 7, 1);
        if (period.contains("-")) {
            String[] parts = period.split("-");
            try {
                int startYear = Integer.parseInt(parts[0].trim());
                if (startYear < 100) startYear += 2000;
                return LocalDate.of(startYear, 4, 1);
            } catch (Exception ignored) {}
        }
        return LocalDate.of(2017, 7, 1);
    }

    private LocalDate parsePeriodEnd(String period) {
        if (period == null) return LocalDate.of(2099, 12, 31);
        if (period.contains("-")) {
            String[] parts = period.split("-");
            try {
                int endYear = Integer.parseInt(parts[parts.length - 1].trim());
                if (endYear < 100) endYear += 2000;
                return LocalDate.of(endYear, 3, 31);
            } catch (Exception ignored) {}
        }
        return LocalDate.of(2099, 12, 31);
    }

    public record BlockFillResult(String html, List<String> missingVars, List<String> flags) {}

    private BlockFillResult fillBlockHtml(
            TemplateBlock block,
            List<String> declaredVars,
            Map<String, Object> contextMap,
            String templateId
    ) {
        return fillTextPlaceholders(block.html() != null ? block.html() : "", declaredVars, contextMap, templateId);
    }

    public BlockFillResult fillTextPlaceholders(
            String rawText,
            List<String> declaredVars,
            Map<String, Object> contextMap,
            String templateId
    ) {
        if (rawText == null || rawText.isBlank()) {
            return new BlockFillResult("", List.of(), List.of());
        }

        Pattern pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
        Matcher matcher = pattern.matcher(rawText);

        StringBuilder sb = new StringBuilder();
        List<String> missingVars = new ArrayList<>();
        List<String> flags = new ArrayList<>();

        Set<String> declaredSet = new HashSet<>(declaredVars != null ? declaredVars : List.of());

        while (matcher.find()) {
            String placeholderKey = matcher.group(1).trim();

            // Ignore cross-references (ref:...) as they are resolved during assembly
            if (placeholderKey.startsWith("ref:")) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("{{" + placeholderKey + "}}"));
                continue;
            }

            // Unknown placeholder check -> fail template bug
            if (!declaredSet.contains(placeholderKey)) {
                throw new IllegalStateException("Template Bug: Unknown placeholder '" + placeholderKey + "' in template " + templateId);
            }

            Object val = contextMap.get(placeholderKey);
            if (val == null || val.toString().isBlank()) {
                missingVars.add(placeholderKey);
                flags.add("missing_" + placeholderKey);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("[[MISSING: " + placeholderKey + "]]"));
            } else {
                String formatted = formatValue(placeholderKey, val);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(formatted));
            }
        }
        matcher.appendTail(sb);

        return new BlockFillResult(sb.toString(), missingVars, flags);
    }

    /**
     * Formats amounts as Indian currency "Rs 4,82,310" and dates as "dd-MM-yyyy".
     */
    public String formatValue(String key, Object val) {
        if (val == null) return "";

        String keyLower = key.toLowerCase();

        // Currency format check
        if (keyLower.contains("amount") || keyLower.contains("tax") || keyLower.contains("demand")
                || keyLower.contains("turnover") || keyLower.contains("interest") || keyLower.contains("penalty")) {
            try {
                BigDecimal bd;
                if (val instanceof Number) {
                    bd = BigDecimal.valueOf(((Number) val).doubleValue());
                } else {
                    bd = new BigDecimal(val.toString().replaceAll("[^0-9.]", ""));
                }
                return formatIndianCurrency(bd);
            } catch (Exception ignored) {}
        }

        // Date format check
        if (keyLower.contains("date") || val instanceof LocalDate || val instanceof OffsetDateTime) {
            try {
                if (val instanceof LocalDate) {
                    return ((LocalDate) val).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                }
                if (val instanceof OffsetDateTime) {
                    return ((OffsetDateTime) val).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                }
                String str = val.toString().trim();
                if (str.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                    LocalDate ld = LocalDate.parse(str.substring(0, 10));
                    return ld.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                }
            } catch (Exception ignored) {}
        }

        return val.toString();
    }

    public static String formatIndianCurrency(BigDecimal amount) {
        if (amount == null) return "Rs 0";

        long longVal = amount.longValue();
        String strVal = String.valueOf(Math.abs(longVal));

        StringBuilder sb = new StringBuilder();
        int len = strVal.length();

        if (len <= 3) {
            sb.append(strVal);
        } else {
            sb.append(strVal.substring(len - 3));
            int pos = len - 3;
            while (pos > 0) {
                int chunk = Math.min(2, pos);
                sb.insert(0, strVal.substring(pos - chunk, pos) + ",");
                pos -= chunk;
            }
        }

        String prefix = longVal < 0 ? "Rs -" : "Rs ";
        return prefix + sb.toString();
    }

    public Map<String, Object> buildContextMap(
            MatchedIssue issue,
            MatchedNoticeInfo noticeInfo,
            DraftingAgent.DraftingInput input
    ) {
        Map<String, Object> map = new HashMap<>();

        // Issue facts
        if (issue.facts() != null) {
            for (Map.Entry<String, Object> entry : issue.facts().entrySet()) {
                map.put("issue." + entry.getKey(), entry.getValue());
                map.put("facts." + entry.getKey(), entry.getValue());
            }
            if (issue.facts().containsKey("amount")) {
                map.put("issue.amount_tax", issue.facts().get("amount"));
            }
        }

        // Notice metadata
        if (noticeInfo != null) {
            if (noticeInfo.din() != null) map.put("notice.din", noticeInfo.din());
            if (noticeInfo.noticeNumber() != null) map.put("notice.notice_number", noticeInfo.noticeNumber());
            if (noticeInfo.issueDate() != null) map.put("notice.issue_date", noticeInfo.issueDate());
            if (noticeInfo.replyDueDate() != null) map.put("notice.reply_due_date", noticeInfo.replyDueDate());
            if (noticeInfo.totalDemandAmount() != null) map.put("notice.total_demand_amount", noticeInfo.totalDemandAmount());
        }

        // Client / Registration input
        if (input != null) {
            if (input.clientLegalName() != null) map.put("client.legal_name", input.clientLegalName());
            if (input.registrationIdentifier() != null) map.put("client.gstin", input.registrationIdentifier());
            if (input.registrationStateName() != null) map.put("client.state", input.registrationStateName());
            if (input.financialYear() != null) map.put("notice.financial_year", input.financialYear());

            if (input.notice() != null) {
                for (Map.Entry<String, Object> entry : input.notice().entrySet()) {
                    map.put("notice." + entry.getKey(), entry.getValue());
                }
            }
        }

        return map;
    }

    private boolean evaluateWhenCondition(String whenExpr, Map<String, Object> context) {
        if (whenExpr == null || whenExpr.isBlank()) return true;

        String expr = whenExpr.trim();
        if (expr.contains("==")) {
            String[] parts = expr.split("==");
            String key = parts[0].trim();
            String expectedVal = parts[1].trim().replaceAll("^'|'$", "").replaceAll("^\"|\"$", "");

            Object actualVal = context.get(key);
            if (actualVal == null) return false;
            return expectedVal.equalsIgnoreCase(actualVal.toString().trim());
        }

        return true;
    }

    private List<TemplateBlock> parseBlocksJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TemplateBlock>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(json);
        }
    }
}
