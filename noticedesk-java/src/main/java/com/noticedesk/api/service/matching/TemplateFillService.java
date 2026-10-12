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

        TemplateSelection selection = selectTemplate(cardId, period);
        ReplyTemplate template = selection.template();
        if (template == null) {
            log.warn("No active template for card_id={} period={}: {}", cardId, period, selection.reason());
            return createNoTemplateResult(cardId, "no_template_available");
        }

        Map<String, Object> contextMap = buildContextMap(issue, noticeInfo, input);
        List<String> declaredVars = template.variables() != null ? template.variables() : List.of();

        List<FilledBlock> filledBlocks = new ArrayList<>();
        List<SourceMapEntry> sourceMapEntries = new ArrayList<>();
        List<String> allMissingVars = new ArrayList<>();
        List<String> allFlags = new ArrayList<>();
        if (selection.crossesVersionBoundary()) {
            log.warn("Issue #{} period {} crosses a template version boundary for card {}; using the version in force on {}",
                    issue.issueNo(), period, cardId, selection.period().start());
            allFlags.add(PERIOD_CROSSES_TEMPLATE_VERSION_FLAG);
        }

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

    /** Draft flag (warn) raised when an issue period spans more than one version of its reply template. */
    public static final String PERIOD_CROSSES_TEMPLATE_VERSION_FLAG = "warn:period_crosses_template_version";

    /**
     * Result of template version selection.
     *
     * @param template               the version in force on the period's start date, or null
     * @param period                 the normalised period, or null when the text could not be read
     * @param crossesVersionBoundary true when the period runs past the chosen version (another version
     *                               applies to part of it)
     * @param reason                 why no template was chosen (log text only)
     */
    public record TemplateSelection(ReplyTemplate template, PeriodNormalizer.Period period,
                                    boolean crossesVersionBoundary, String reason) {}

    /** Selects the active (or, when allowed, draft) template version for the card that is in force for the period. */
    public ReplyTemplate selectTemplateForCardAndPeriod(String cardId, String period) {
        return selectTemplate(cardId, period).template();
    }

    /**
     * Picks the highest template version whose effective dates contain the START of the issue period
     * (normalised by {@link PeriodNormalizer}). A period that cannot be read only matches templates with
     * no effective dates.
     */
    public TemplateSelection selectTemplate(String cardId, String period) {
        boolean allowDraft = properties == null || properties.getMatching() == null || properties.getMatching().isAllowDraftCards();

        List<ReplyTemplate> candidates = loadTemplatesForCard(cardId, allowDraft);
        if (candidates.isEmpty()) {
            return new TemplateSelection(null, null, false,
                    "no " + (allowDraft ? "active or draft" : "active") + " template exists for the card");
        }

        Optional<PeriodNormalizer.Period> parsed = PeriodNormalizer.parse(period);
        if (parsed.isEmpty()) {
            for (ReplyTemplate t : candidates) {
                if (t.effectiveFrom() == null && t.effectiveTo() == null) {
                    return new TemplateSelection(t, null, false, null);
                }
            }
            return new TemplateSelection(null, null, false, "period could not be normalised");
        }

        PeriodNormalizer.Period p = parsed.get();
        for (ReplyTemplate t : candidates) {
            if (inForceOn(t, p.start())) {
                boolean crosses = (t.effectiveTo() != null && p.end().isAfter(t.effectiveTo()))
                        || candidates.stream().anyMatch(o -> o != t && o.effectiveFrom() != null
                                && o.effectiveFrom().isAfter(p.start()) && !o.effectiveFrom().isAfter(p.end()));
                return new TemplateSelection(t, p, crosses, null);
            }
        }
        return new TemplateSelection(null, p, false, "no template version in force on " + p.start());
    }

    private static boolean inForceOn(ReplyTemplate t, LocalDate date) {
        return (t.effectiveFrom() == null || !date.isBefore(t.effectiveFrom()))
                && (t.effectiveTo() == null || !date.isAfter(t.effectiveTo()));
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

        // a template written "Rs {{issue.amount_tax}}" must not print "Rs Rs 1,00,000"
        return new BlockFillResult(collapseDuplicateCurrency(sb.toString()), missingVars, flags);
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

    /** A currency prefix (Rs / Rs. / INR / ₹) directly followed by another one and an amount. */
    private static final Pattern DUPLICATE_CURRENCY =
            Pattern.compile("(?i)(?:\\bRs\\.?|\\bINR|₹)\\s*(?=(?:Rs\\.?|INR|₹)\\s*-?\\d)");

    /**
     * Renders an amount once: drops a currency prefix written in the template text when the filled value
     * ({@link #formatIndianCurrency}, "Rs 1,00,000") carries its own, e.g. "Rs Rs 1,00,000" / "Rs. Rs 1,00,000"
     * / "INR Rs 1,00,000" -> "Rs 1,00,000".
     */
    public static String collapseDuplicateCurrency(String text) {
        if (text == null || text.isEmpty()) return text;
        return DUPLICATE_CURRENCY.matcher(text).replaceAll("");
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
            if (noticeInfo.taxPeriod() != null && !noticeInfo.taxPeriod().isBlank()) map.put("notice.tax_period", noticeInfo.taxPeriod());
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
                // the client's date of receipt comes from intake (notices.receipt_date), never from the model
                Object receipt = input.notice().get("receipt_date");
                if (receipt != null) map.putIfAbsent("notice.date_of_receipt", receipt);
            }
        }
        // tax period: matcher notice block first, else the issue's own period
        if (!map.containsKey("notice.tax_period") && map.get("issue.period") != null) {
            map.put("notice.tax_period", map.get("issue.period"));
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
