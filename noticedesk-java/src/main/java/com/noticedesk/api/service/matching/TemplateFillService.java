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
import com.noticedesk.api.service.learned.LearnedLibrary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Approved learned-library entries are templates of their own (LRN-... cards, include-learned on only). */
    private LearnedLibrary learnedLibrary;

    @Autowired(required = false)
    public void setLearnedLibrary(LearnedLibrary learnedLibrary) {
        this.learnedLibrary = learnedLibrary;
    }

    public record FilledBlock(
            String blockId,
            int section,
            String filledHtml,
            List<String> citations,
            List<String> missingVars,
            List<String> flags
    ) {}

    /**
     * @param flagDetails details per flag in {@code flags} (e.g. the card id and the two date windows behind
     *                    {@link #FLAG_TEMPLATE_WINDOW_MISMATCH}); empty for flags without details
     */
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
            boolean periodMissing,
            Map<String, Map<String, Object>> flagDetails
    ) {
        public FilledTemplateResult {
            if (flagDetails == null) flagDetails = Map.of();
        }

        /** A result without flag details. */
        public FilledTemplateResult(String templateId, int templateVersion, String cardId, String summaryLine,
                                    List<FilledBlock> blocks, List<SourceMapEntry> sourceMapEntries, List<String> missingVars,
                                    List<String> flags, List<String> documents, List<String> proceduralObjectionOptions,
                                    boolean periodMissing) {
            this(templateId, templateVersion, cardId, summaryLine, blocks, sourceMapEntries, missingVars, flags, documents,
                    proceduralObjectionOptions, periodMissing, Map.of());
        }

        /** True when the issue was filled from a template (a block list, not the NONE / MISSING_PERIOD result). */
        public boolean hasTemplate() {
            return blocks != null && !blocks.isEmpty();
        }
    }

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
            // only when the card has no template row at all: a template that exists is always used (see selectTemplate)
            log.warn("No active template for card_id={} period={}: {}", cardId, period, selection.reason());
            return createNoTemplateResult(cardId, "no_template_available");
        }

        Map<String, Object> contextMap = buildContextMap(issue, noticeInfo, input);
        List<String> declaredVars = template.variables() != null ? template.variables() : List.of();

        List<FilledBlock> filledBlocks = new ArrayList<>();
        List<SourceMapEntry> sourceMapEntries = new ArrayList<>();
        List<String> allMissingVars = new ArrayList<>();
        List<String> allFlags = new ArrayList<>();
        Map<String, Map<String, Object>> flagDetails = new LinkedHashMap<>();
        if (selection.crossesVersionBoundary()) {
            log.warn("Issue #{} period {} crosses a template version boundary for card {}; using version {} (in force on {})",
                    issue.issueNo(), period, cardId, template.version(), selection.period().end());
            allFlags.add(PERIOD_CROSSES_TEMPLATE_VERSION_FLAG);
        }
        for (String f : selection.flags()) {
            if (FLAG_TEMPLATE_WINDOW_MISMATCH.equals(f)) {
                log.warn("Issue #{}: no template version of card {} covers the period {} ({} to {}); nearest version {} ({} to {}) used",
                        issue.issueNo(), cardId, period, selection.period().start(), selection.period().end(),
                        template.version(), template.effectiveFrom(), template.effectiveTo());
            } else if (FLAG_PERIOD_NOT_NORMALISED.equals(f)) {
                log.warn("Issue #{}: period '{}' could not be read; latest template version {} of card {} used",
                        issue.issueNo(), period, template.version(), cardId);
            }
            allFlags.add(f);
            Map<String, Object> d = selection.flagDetails().get(f);
            if (d != null) flagDetails.put(f, d);
        }

        if (isProvisional(template)) {
            log.info("Issue #{} filled from PROVISIONAL template {} v{} (promoted from an AI section; partner to confirm)",
                    issue.issueNo(), template.templateId(), template.version());
            allFlags.add(FLAG_PROVISIONAL_TEMPLATE);
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
                false, flagDetails
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
     * Draft flag (info, check FLAG_PROVISIONAL_TEMPLATE) raised when the issue was filled from a
     * 'provisional' reply template: one promoted from an approved AI issue section, not yet CA-reviewed.
     * The partner note names the template so the partner can confirm or retire it.
     */
    public static final String FLAG_PROVISIONAL_TEMPLATE = "info:provisional_template";

    /** Template status of a template promoted from an AI issue section. */
    public static final String STATUS_PROVISIONAL = "provisional";

    public static boolean isProvisional(ReplyTemplate t) {
        return t != null && STATUS_PROVISIONAL.equalsIgnoreCase(t.status());
    }

    /**
     * Draft flag (warn) raised when no template version of the card overlaps the issue period and the version
     * with the nearest effective window was used. Details: card id, period and template window dates.
     */
    public static final String FLAG_TEMPLATE_WINDOW_MISMATCH = "warn:template_window_mismatch";

    /**
     * Draft flag (warn) raised when the issue period text could not be read as a date range and the latest
     * template version of the card was used.
     */
    public static final String FLAG_PERIOD_NOT_NORMALISED = "warn:period_not_normalised";

    /** GST came into force on 1 July 2017: a period starting earlier (e.g. "FY 2017-18") is read from this date. */
    public static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

    /**
     * Result of template version selection.
     *
     * @param template               the chosen version, or null only when the card has no template at all
     * @param period                 the normalised period (start clamped to {@link #GST_START}), or null when the
     *                               text could not be read
     * @param crossesVersionBoundary true when the period runs past the chosen version (another version
     *                               applies to part of it)
     * @param reason                 why no template was chosen (log text only)
     * @param flags                  selection flags ({@link #FLAG_TEMPLATE_WINDOW_MISMATCH}, {@link #FLAG_PERIOD_NOT_NORMALISED})
     * @param flagDetails            details per flag
     */
    public record TemplateSelection(ReplyTemplate template, PeriodNormalizer.Period period,
                                    boolean crossesVersionBoundary, String reason,
                                    List<String> flags, Map<String, Map<String, Object>> flagDetails) {
        public TemplateSelection {
            if (flags == null) flags = List.of();
            if (flagDetails == null) flagDetails = Map.of();
        }

        TemplateSelection(ReplyTemplate template, PeriodNormalizer.Period period, boolean crossesVersionBoundary, String reason) {
            this(template, period, crossesVersionBoundary, reason, List.of(), Map.of());
        }
    }

    /** Selects the active / provisional (or, when allowed, draft) template version for the card that is in force for the period. */
    public ReplyTemplate selectTemplateForCardAndPeriod(String cardId, String period) {
        return selectTemplate(cardId, period).template();
    }

    /**
     * Picks the template version of the card for the issue period (normalised by {@link PeriodNormalizer},
     * start clamped to {@link #GST_START}):
     * <ol>
     *   <li>among the versions whose effective window OVERLAPS the period, the one in force on the period's
     *       end, else the one in force on its start, else the highest overlapping version;</li>
     *   <li>when no version overlaps, the version whose window is nearest to the period (smallest gap),
     *       with {@link #FLAG_TEMPLATE_WINDOW_MISMATCH};</li>
     *   <li>when the period cannot be read, the latest version, with {@link #FLAG_PERIOD_NOT_NORMALISED}.</li>
     * </ol>
     * The template is null only when the card has no active / provisional (/ draft) template row at all.
     */
    public TemplateSelection selectTemplate(String cardId, String period) {
        boolean allowDraft = properties == null || properties.getMatching() == null || properties.getMatching().isAllowDraftCards();

        List<ReplyTemplate> candidates = loadTemplatesForCard(cardId, allowDraft);   // highest version first
        if (candidates.isEmpty()) {
            return new TemplateSelection(null, null, false,
                    "no " + (allowDraft ? "active, provisional or draft" : "active or provisional") + " template exists for the card");
        }

        Optional<PeriodNormalizer.Period> parsed = PeriodNormalizer.parse(period);
        if (parsed.isEmpty()) {
            ReplyTemplate latest = candidates.get(0);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("card_id", cardId);
            details.put("period", period != null ? period : "");
            details.put("template_id", latest.templateId());
            details.put("template_version", latest.version());
            return new TemplateSelection(latest, null, false, null,
                    List.of(FLAG_PERIOD_NOT_NORMALISED), Map.of(FLAG_PERIOD_NOT_NORMALISED, details));
        }

        PeriodNormalizer.Period p = clampToGst(parsed.get());
        List<ReplyTemplate> overlapping = candidates.stream().filter(t -> overlaps(t, p)).toList();
        if (!overlapping.isEmpty()) {
            ReplyTemplate chosen = overlapping.stream().filter(t -> inForceOn(t, p.end())).findFirst()
                    .or(() -> overlapping.stream().filter(t -> inForceOn(t, p.start())).findFirst())
                    .orElse(overlapping.get(0));
            // another version applies to part of the period when any version boundary falls inside it
            boolean crosses = candidates.stream().anyMatch(t -> boundaryInside(t, p));
            return new TemplateSelection(chosen, p, crosses, null);
        }

        // no version covers any part of the period: the nearest window (ties -> highest version)
        ReplyTemplate nearest = candidates.get(0);
        long best = gapDays(nearest, p);
        for (ReplyTemplate t : candidates) {
            long gap = gapDays(t, p);
            if (gap < best) {
                best = gap;
                nearest = t;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("card_id", cardId);
        details.put("period", period);
        details.put("period_start", p.start().toString());
        details.put("period_end", p.end().toString());
        details.put("template_id", nearest.templateId());
        details.put("template_version", nearest.version());
        details.put("template_effective_from", nearest.effectiveFrom() != null ? nearest.effectiveFrom().toString() : "");
        details.put("template_effective_to", nearest.effectiveTo() != null ? nearest.effectiveTo().toString() : "");
        details.put("gap_days", best);
        return new TemplateSelection(nearest, p, false, null,
                List.of(FLAG_TEMPLATE_WINDOW_MISMATCH), Map.of(FLAG_TEMPLATE_WINDOW_MISMATCH, details));
    }

    /** The period with its start (and, for a wholly pre-GST period, its end) moved up to {@link #GST_START}. */
    static PeriodNormalizer.Period clampToGst(PeriodNormalizer.Period p) {
        if (!p.start().isBefore(GST_START)) return p;
        LocalDate end = p.end().isBefore(GST_START) ? GST_START : p.end();
        return new PeriodNormalizer.Period(GST_START, end);
    }

    private static boolean inForceOn(ReplyTemplate t, LocalDate date) {
        return (t.effectiveFrom() == null || !date.isBefore(t.effectiveFrom()))
                && (t.effectiveTo() == null || !date.isAfter(t.effectiveTo()));
    }

    /** True when the template's effective window and the period share at least one day. */
    private static boolean overlaps(ReplyTemplate t, PeriodNormalizer.Period p) {
        return (t.effectiveFrom() == null || !t.effectiveFrom().isAfter(p.end()))
                && (t.effectiveTo() == null || !t.effectiveTo().isBefore(p.start()));
    }

    /** True when the template starts after the period's first day or ends before its last day (a version boundary inside the period). */
    private static boolean boundaryInside(ReplyTemplate t, PeriodNormalizer.Period p) {
        boolean startsInside = t.effectiveFrom() != null && t.effectiveFrom().isAfter(p.start()) && !t.effectiveFrom().isAfter(p.end());
        boolean endsInside = t.effectiveTo() != null && !t.effectiveTo().isBefore(p.start()) && t.effectiveTo().isBefore(p.end());
        return startsInside || endsInside;
    }

    /** Days between the period and the template window (0 when they overlap). */
    private static long gapDays(ReplyTemplate t, PeriodNormalizer.Period p) {
        if (overlaps(t, p)) return 0;
        if (t.effectiveFrom() != null && t.effectiveFrom().isAfter(p.end())) {
            return java.time.temporal.ChronoUnit.DAYS.between(p.end(), t.effectiveFrom());
        }
        return java.time.temporal.ChronoUnit.DAYS.between(t.effectiveTo(), p.start());
    }

    private List<ReplyTemplate> loadTemplatesForCard(String cardId, boolean allowDraft) {
        List<ReplyTemplate> list = new ArrayList<>();

        if (inMemoryTemplates.containsKey(cardId)) {
            for (ReplyTemplate t : inMemoryTemplates.get(cardId)) {
                // 'provisional' (promoted from an AI section) is usable like 'active'; the fill adds an info flag
                if ("active".equalsIgnoreCase(t.status()) || isProvisional(t) || (allowDraft && "draft".equalsIgnoreCase(t.status()))) {
                    list.add(t);
                }
            }
        }

        if (learnedLibrary != null) {
            // 'provisional' status: the fill raises FLAG_PROVISIONAL_TEMPLATE, so the partner note names the entry
            learnedLibrary.templateFor(cardId).ifPresent(list::add);
        }

        if (jdbcTemplate != null) {
            String sql = """
                    SELECT template_id, version, card_id, effective_from, effective_to, status,
                           blocks, variables, documents, summary_line, procedural_objection_options,
                           approved_by, approved_at, created_at
                    FROM reply_templates
                    WHERE card_id = ? AND status IN (%s)
                    ORDER BY version DESC
                    """.formatted(allowDraft ? "'active', 'provisional', 'draft'" : "'active', 'provisional'");
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

        // Issue facts: the matcher's generic facts ("period", "amount", "legal_section", and any value the template
        // variable step merged in without a prefix) read as issue.<key> and facts.<key>; a fact stored under a full
        // variable name (notice.* / client.*, see factKeyForVariable) is applied after the pipeline's own values below
        Map<String, Object> prefixedFacts = new LinkedHashMap<>();
        if (issue.facts() != null) {
            for (Map.Entry<String, Object> entry : issue.facts().entrySet()) {
                String key = entry.getKey();
                if (hasVariablePrefix(key)) {
                    prefixedFacts.put(key, entry.getValue());
                    continue;
                }
                map.put("issue." + key, entry.getValue());
                map.put("facts." + key, entry.getValue());
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
        // notice.reference (used by promoted templates and the stage templates): notice number, else DIN
        if (!map.containsKey("notice.reference")) {
            Object ref = map.get("notice.notice_number") != null ? map.get("notice.notice_number") : map.get("notice.din");
            if (ref != null && !ref.toString().isBlank()) map.put("notice.reference", ref);
        }

        // Date semantics: notice.issue_date is the date of the document being answered (the input document, from
        // the matcher's notice block or the intake record). A template that needs the ORDER's date uses an issue.*
        // variable (issue.order_date), which the template variable step extracts: an issue.* / facts.* value present
        // in the facts always wins; a notice.* / client.* value from the facts is only a fallback for one the
        // pipeline does not have.
        for (Map.Entry<String, Object> entry : prefixedFacts.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("issue.") || key.startsWith("facts.")) {
                map.put(key, entry.getValue());
            } else if (isBlank(map.get(key))) {
                map.put(key, entry.getValue());
            }
        }

        return map;
    }

    /** The placeholder prefixes a template variable may carry. */
    private static final List<String> VARIABLE_PREFIXES = List.of("issue.", "facts.", "notice.", "client.");

    static boolean hasVariablePrefix(String key) {
        if (key == null) return false;
        for (String p : VARIABLE_PREFIXES) if (key.startsWith(p)) return true;
        return false;
    }

    private static boolean isBlank(Object v) {
        return v == null || v.toString().isBlank();
    }

    /**
     * The facts-map key a value extracted for a template variable is stored under so that {@link #buildContextMap}
     * resolves the placeholder: {@code issue.X} and {@code facts.X} become {@code X} (like the matcher's own
     * facts, read back as both {@code issue.X} and {@code facts.X}); a {@code notice.*} / {@code client.*} variable
     * keeps its full name (a fallback the pipeline's own value wins over); any other name is kept as given.
     */
    public static String factKeyForVariable(String variableName) {
        String n = variableName != null ? variableName.trim() : "";
        if (n.startsWith("issue.")) return n.substring("issue.".length());
        if (n.startsWith("facts.")) return n.substring("facts.".length());
        return n;
    }

    /**
     * The template's declared variables whose value is unknown in {@code contextMap} (absent or blank), in the
     * template's order, each once; {@code ref:} cross-references are never variables. These are the variables the
     * template variable step asks the model for: a {@code client.*} / {@code notice.*} value the pipeline already
     * has and the matcher's own facts ({@code issue.period}, {@code issue.amount_tax}, {@code issue.legal_section})
     * are known and excluded.
     */
    public static List<String> unknownVariables(ReplyTemplate template, Map<String, Object> contextMap) {
        if (template == null || template.variables() == null) return List.of();
        Map<String, Object> ctx = contextMap != null ? contextMap : Map.of();
        List<String> out = new ArrayList<>();
        for (String raw : template.variables()) {
            String v = raw != null ? raw.trim() : "";
            if (v.isEmpty() || v.startsWith("ref:") || out.contains(v)) continue;
            if (isBlank(ctx.get(v))) out.add(v);
        }
        return List.copyOf(out);
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
