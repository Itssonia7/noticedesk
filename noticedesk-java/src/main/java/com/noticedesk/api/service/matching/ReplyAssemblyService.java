package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.SourceMapEntry;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.TemplateFillService.FilledBlock;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v7 Stage 2/3: assembles the 15-section reply from filled templates (code) and, for partial / unmatched
 * issues, the sanitised AI output. Internal information (card IDs, match status, template IDs, errors,
 * strength notes) is written to Section 13 only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplyAssemblyService {

    /** "Para 3: ...", "Para 3(c): ...", "Paragraph 3.1 - ..." inside an &lt;li&gt;; group 2 = sub-para label. */
    private static final Pattern PARA_LI = Pattern.compile(
            "<li>\\s*(?:<(?:strong|b)>)?\\s*Para(?:graph)?\\s*(\\d+)((?:\\s*\\(\\s*[a-z0-9]{1,5}\\s*\\)|\\.\\d+)*)"
            + "\\s*[:.]?\\s*(?:</(?:strong|b)>)?\\s*[:.\\-–—]?\\s*(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** para_map keys that name a paragraph category (values = notice paragraph numbers). */
    private static final Set<String> PARA_CATEGORIES = Set.of("record", "issue", "demand", "directions");
    /** Other para_map keys are notice paragraph numbers ("3", "3.1", "Para 3"; values = issue numbers). */
    private static final Pattern PARA_KEY = Pattern.compile("(?i)^\\s*(?:para(?:graph)?\\.?\\s*)?(\\d{1,3})\\b");
    private static final Pattern STAGE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_.]+)\\s*\\}\\}");
    /**
     * Wraps AI text and gathered document lists in the sections the proceeding-consistency check reads
     * (01, 14), so it judges only the code-built notice description and the stage template (DraftCheckService).
     */
    public static final String FREE_TEXT_START = "<!-- free-text -->";
    public static final String FREE_TEXT_END = "<!-- /free-text -->";
    /** Word budget for the high-stakes narrative in Section 01; the rest moves to Section 03. */
    static final int EXEC_SUMMARY_MAX_WORDS = 150;
    /** An issue bullet in Section 01 longer than this keeps only its first sentence. */
    static final int SUMMARY_BULLET_MAX_WORDS = 40;

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    // In-memory stage templates for testing or fallback
    private final Map<String, StageTemplate> inMemoryStageTemplates = new HashMap<>();

    public void registerInMemoryStageTemplate(StageTemplate stageTemplate) {
        inMemoryStageTemplates.put(stageTemplate.stage().toLowerCase(), stageTemplate);
    }

    public record AssembledSection(
            int num,
            String title,
            String bodyHtml
    ) {}

    public record AssembledReplyResult(
            List<AssembledSection> sections,
            List<SourceMapEntry> paragraphSourceMap,
            List<String> flags,
            List<String> missingMarkers,
            List<String> pendingAiMarkers
    ) {}

    /** One piece of sanitised AI HTML for section 4, 5 or 6, optionally anchored after a template block. */
    public record AiFragment(int section, String afterBlock, String html) {}

    /** Stage 3 output for one issue. {@code writer} is "partial", "new_issue" or "high_stakes" (internal only). */
    public record AiIssueContent(
            int issueNo,
            String writer,
            List<AiFragment> fragments,
            List<String> documents,
            String summaryLine
    ) {}

    /** High-stakes narrative for sections 01 and 03 (printed after the code-built fact paragraphs). */
    public record HighStakesContent(String section01Html, String section03Html) {}

    private enum IssueMode { TEMPLATE, MISSING_PERIOD, AI }

    /** Stage 2 only (no AI content): partial / unmatched issues get [[PENDING_AI]] markers. */
    public AssembledReplyResult assembleReply(
            MatchingResult matchingResult,
            List<FilledTemplateResult> filledTemplates,
            DraftingAgent.DraftingInput input,
            String stageName
    ) {
        return assembleReply(matchingResult, filledTemplates, input, stageName, Map.of(), null, List.of());
    }

    /**
     * True when an issue cannot be written from its template by code and needs a Stage 3 writer:
     * partial / unmatched issues, and "full" matches whose card has no usable template for the period.
     */
    public static boolean needsAiWriter(MatchedIssue issue, FilledTemplateResult ft) {
        return modeFor(issue, ft) == IssueMode.AI;
    }

    private static IssueMode modeFor(MatchedIssue issue, FilledTemplateResult ft) {
        if ("full".equalsIgnoreCase(issue.status())) {
            if (ft != null && ft.periodMissing()) return IssueMode.MISSING_PERIOD;
            if (ft != null && ft.blocks() != null && !ft.blocks().isEmpty()) return IssueMode.TEMPLATE;
        }
        return IssueMode.AI;
    }

    public AssembledReplyResult assembleReply(
            MatchingResult matchingResult,
            List<FilledTemplateResult> filledTemplates,
            DraftingAgent.DraftingInput input,
            String stageName,
            Map<Integer, AiIssueContent> aiContent,
            HighStakesContent highStakes,
            List<String> internalNotes
    ) {
        List<AssembledSection> rawSections = new ArrayList<>();
        List<SourceMapEntry> finalSourceMap = new ArrayList<>();
        List<String> assemblyFlags = new ArrayList<>();
        List<String> missingMarkers = new ArrayList<>();
        List<String> pendingAiMarkers = new ArrayList<>();
        List<String> notes = new ArrayList<>(internalNotes != null ? internalNotes : List.of());
        Map<Integer, AiIssueContent> ai = aiContent != null ? aiContent : Map.of();

        MatchedNoticeInfo noticeInfo = matchingResult != null ? matchingResult.notice() : null;
        List<MatchedIssue> matchedIssues = matchingResult != null && matchingResult.issues() != null ? matchingResult.issues() : List.of();

        String normalizedStage = stageName != null && !stageName.isBlank() ? stageName.toLowerCase().trim() : StageDetector.UNKNOWN;
        StageTemplate stageTpl = loadStageTemplate(normalizedStage);

        Map<Integer, FilledTemplateResult> templateMap = new HashMap<>();
        if (filledTemplates != null) {
            for (int i = 0; i < filledTemplates.size(); i++) {
                int issueNo = (i < matchedIssues.size()) ? matchedIssues.get(i).issueNo() : (i + 1);
                templateMap.put(issueNo, filledTemplates.get(i));
            }
        }

        String noticeSec = noticeSection(input);
        if (noticeSec.startsWith("[[MISSING")) {
            addOnce(missingMarkers, "notice_section");
        }
        String noticeRef = noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "[[MISSING: notice_number]]";
        String noticeDate = noticeInfo != null && noticeInfo.issueDate() != null ? formatDateStr(noticeInfo.issueDate()) : "[[MISSING: issue_date]]";
        String clientName = formatClientName(input != null ? input.clientLegalName() : null);
        String gstin = input != null && input.registrationIdentifier() != null ? input.registrationIdentifier() : "[[MISSING: client_gstin]]";
        String fy = input != null && input.financialYear() != null ? input.financialYear() : "[[MISSING: financial_year]]";
        String totalDemand = noticeInfo != null && noticeInfo.totalDemandAmount() != null
                ? TemplateFillService.formatIndianCurrency(BigDecimal.valueOf(noticeInfo.totalDemandAmount()))
                : "[[MISSING: total_demand]]";

        // Anchored template blocks per AI issue (partial matches render only the blocks the writer anchored to)
        Map<Integer, Set<String>> anchoredBlocks = new HashMap<>();
        for (MatchedIssue issue : matchedIssues) {
            AiIssueContent c = ai.get(issue.issueNo());
            Set<String> anchors = new HashSet<>();
            if (c != null && c.fragments() != null) {
                for (AiFragment f : c.fragments()) {
                    if (f.afterBlock() != null) anchors.add(f.afterBlock());
                }
            }
            anchoredBlocks.put(issue.issueNo(), anchors);
        }

        // ---------------------------------------------------------------- Section 04 (built first: its para numbers are referenced by 01/05)
        StringBuilder sec04 = new StringBuilder();
        Map<String, String> blockIdToRefTag = new HashMap<>();
        Map<Integer, String> issueNoToRefTag = new HashMap<>();
        int[] globalParaNo = {1};
        int[] sec04Sub = {1};

        for (MatchedIssue issue : matchedIssues) {
            sec04.append("<h3>Issue #").append(issue.issueNo()).append(" Grounds of Defence</h3>");
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            issueNoToRefTag.put(issue.issueNo(), "4." + sec04Sub[0]);
            appendIssueBody(sec04, 4, issue, ft, ai.get(issue.issueNo()), anchoredBlocks.get(issue.issueNo()),
                    sec04Sub, globalParaNo, blockIdToRefTag, finalSourceMap, pendingAiMarkers, missingMarkers, assemblyFlags, notes);
        }

        // ---------------------------------------------------------------- Section 01
        StringBuilder sec01 = new StringBuilder();
        sec01.append("<p><strong>Executive Summary:</strong> Reply to Notice Ref ").append(noticeRef)
             .append(" dated ").append(noticeDate)
             .append(" issued under ").append(noticeSec)
             .append(" for GSTIN ").append(gstin)
             .append(". Total Demand Amount: ").append(totalDemand).append(".</p>");
        String sec01Overflow = "";
        if (highStakes != null && highStakes.section01Html() != null && !highStakes.section01Html().isBlank()) {
            DraftTextCleanup.WordSplit split = DraftTextCleanup.limitWords(highStakes.section01Html(), EXEC_SUMMARY_MAX_WORDS);
            sec01.append(freeText(split.kept()));
            sec01Overflow = split.overflow();
            if (!sec01Overflow.isBlank()) {
                notes.add("High-stakes executive summary was longer than " + EXEC_SUMMARY_MAX_WORDS + " words ("
                        + DraftTextCleanup.wordCount(highStakes.section01Html()) + "); the rest was moved to the end of Section 03.");
            }
        }
        sec01.append("<ul>");
        for (MatchedIssue issue : matchedIssues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            AiIssueContent c = ai.get(issue.issueNo());
            sec01.append("<li><strong>Issue #").append(issue.issueNo()).append("</strong>");
            String factsLabel = issueFactsLabel(issue);
            if (!factsLabel.isEmpty()) sec01.append(" (").append(factsLabel).append(")");
            sec01.append(": ");
            switch (modeFor(issue, ft)) {
                case TEMPLATE -> sec01.append(nonBlank(ft.summaryLine(), "Grounds of defence are set out at para {{ref:ISSUE-" + issue.issueNo() + "}}."));
                case MISSING_PERIOD -> {
                    sec01.append("[[MISSING: period]]");
                    addOnce(missingMarkers, "period");
                }
                case AI -> {
                    if (c == null || c.fragments() == null || c.fragments().isEmpty()) {
                        sec01.append(pendingMarker(issue.issueNo()));
                        addOnce(pendingAiMarkers, "PENDING_AI: Issue " + issue.issueNo());
                        addOnce(assemblyFlags, "pending_ai_writer");
                    } else if (c.summaryLine() != null && !c.summaryLine().isBlank()) {
                        sec01.append(freeText(oneLineSummary(c.summaryLine())));
                    } else {
                        sec01.append("Grounds of defence are set out at para {{ref:ISSUE-").append(issue.issueNo()).append("}}.");
                    }
                }
            }
            sec01.append("</li>");
        }
        sec01.append("</ul>");
        rawSections.add(new AssembledSection(1, "01 Executive Summary", sec01.toString()));

        // ---------------------------------------------------------------- Section 02
        StringBuilder sec02 = new StringBuilder();
        sec02.append("<p>The taxpayer ").append(clientName)
             .append(" has received notice reference ").append(noticeRef)
             .append(" dated ").append(noticeDate);
        if (noticeInfo != null && noticeInfo.authority() != null && !noticeInfo.authority().isBlank()) {
            sec02.append(", issued by the ").append(noticeInfo.authority()).append(",");
        }
        sec02.append(" under ").append(noticeSec).append(" of the ").append(lawName(input))
             .append(", for Financial Year ").append(fy).append(".</p>");
        sec02.append("<p>The notice raises ").append(matchedIssues.size()).append(matchedIssues.size() == 1 ? " issue" : " issues")
             .append(", each answered in Section 04 and paragraph-wise in Section 05.</p>");
        rawSections.add(new AssembledSection(2, "02 Notice Understanding", sec02.toString()));

        // ---------------------------------------------------------------- Section 03
        StringBuilder sec03 = new StringBuilder();
        String state = registrationState(input);
        if (state == null) {
            state = "[[MISSING: state]]";
            addOnce(missingMarkers, "state");
            addOnce(assemblyFlags, "warn:missing_state");
        }
        sec03.append("<p>").append(clientName).append(" (GSTIN: ").append(gstin)
             .append(") is registered under the GST law in the State of ").append(state).append(".</p>");
        sec03.append("<p>Notice reference ").append(noticeRef).append(" dated ").append(noticeDate)
             .append(" pertains to Financial Year ").append(fy).append(".</p>");
        String taxPeriod = taxPeriod(noticeInfo, input, matchedIssues);
        String receiptDate = dateOfReceipt(input, matchedIssues);
        sec03.append("<p>The notice covers the tax period ").append(orMissing(taxPeriod, "tax_period", missingMarkers, assemblyFlags))
             .append(" and was received by the taxpayer on ").append(orMissing(receiptDate, "date_of_receipt", missingMarkers, assemblyFlags))
             .append(".</p>");
        if (highStakes != null && highStakes.section03Html() != null && !highStakes.section03Html().isBlank()) {
            sec03.append(highStakes.section03Html());
        }
        if (!sec01Overflow.isBlank()) {
            sec03.append(sec01Overflow);   // executive-summary narrative beyond the Section 01 word budget
        }
        rawSections.add(new AssembledSection(3, "03 Factual Background", sec03.toString()));

        rawSections.add(new AssembledSection(4, "04 Issue-wise Response", sec04.toString()));

        // ---------------------------------------------------------------- Section 05
        rawSections.add(new AssembledSection(5, "05 Para-wise Reply",
                buildParaWiseReply(matchingResult, input, matchedIssues, ai, missingMarkers, assemblyFlags, notes)));

        // ---------------------------------------------------------------- Section 06
        StringBuilder sec06 = new StringBuilder();
        int[] sec06Sub = {1};
        for (MatchedIssue issue : matchedIssues) {
            sec06.append("<h3>Legal Submissions for Issue #").append(issue.issueNo()).append("</h3>");
            appendIssueBody(sec06, 6, issue, templateMap.get(issue.issueNo()), ai.get(issue.issueNo()), anchoredBlocks.get(issue.issueNo()),
                    sec06Sub, globalParaNo, blockIdToRefTag, finalSourceMap, pendingAiMarkers, missingMarkers, assemblyFlags, notes);
        }
        rawSections.add(new AssembledSection(6, "06 Legal Submissions", sec06.toString()));

        // ---------------------------------------------------------------- Section 07
        rawSections.add(new AssembledSection(7, "07 Procedural Objections", "<p>[[PARTNER: add procedural objections after checklist review]]</p>"));

        // ---------------------------------------------------------------- Section 08
        boolean reliesOnThirdParty = input != null && input.notice() != null && Boolean.TRUE.equals(input.notice().get("relies_on_third_party_material"));
        String sec08;
        if (reliesOnThirdParty) {
            String sec08Tpl = (stageTpl != null && stageTpl.sections() != null) ? stageTpl.sections().get("08") : null;
            sec08 = (sec08Tpl != null && !sec08Tpl.isBlank()) ? sec08Tpl
                    : "<p>Request for cross-examination of third-party witnesses and inspection of underlying statements relied upon by the department.</p>";
        } else {
            sec08 = "<p>Not applicable.</p>";
        }
        rawSections.add(new AssembledSection(8, "08 Cross-Examination Request", sec08));

        // ---------------------------------------------------------------- Section 09
        rawSections.add(new AssembledSection(9, "09 Related Context from Other Registrations", "<p>Not applicable.</p>"));

        // ---------------------------------------------------------------- Documents (10 / 11 / 14)
        List<String> documents = collectDocuments(matchedIssues, templateMap, ai, anchoredBlocks);

        StringBuilder sec10 = new StringBuilder();
        if (documents.isEmpty()) {
            sec10.append("<p>[[PARTNER: list the documents to be enclosed]]</p>");
        } else {
            sec10.append("<p>The following documents are enclosed in support of the reply:</p><ol>");
            for (String d : documents) sec10.append("<li>").append(d).append("</li>");
            sec10.append("</ol>");
        }
        rawSections.add(new AssembledSection(10, "10 Documents Enclosed", sec10.toString()));

        StringBuilder sec11 = new StringBuilder();
        if (documents.isEmpty()) {
            sec11.append("<p>[[PARTNER: prepare the annexure index once documents are listed]]</p>");
        } else {
            sec11.append("<p><strong>Annexure Index:</strong></p><table><tr><th>Annexure</th><th>Document Description</th></tr>");
            for (int i = 0; i < documents.size(); i++) {
                sec11.append("<tr><td>Annexure A-").append(i + 1).append("</td><td>").append(documents.get(i)).append("</td></tr>");
            }
            sec11.append("</table>");
        }
        rawSections.add(new AssembledSection(11, "11 Annexure Index", sec11.toString()));

        // ---------------------------------------------------------------- Section 12
        Map<String, String> stageVars = new HashMap<>();
        stageVars.put("notice.section", noticeSec);
        stageVars.put("notice.type", formatFullNoticeType(noticeSec));
        stageVars.put("notice.reference", noticeRef);
        stageVars.put("notice.notice_number", noticeRef);
        stageVars.put("notice.issue_date", noticeDate);
        stageVars.put("client.legal_name", clientName);
        stageVars.put("notice.tax_period", taxPeriod);
        stageVars.put("notice.date_of_receipt", receiptDate);
        String sec12Body = fillStagePlaceholders(
                getStageSectionOrMissing(stageTpl, "12", normalizedStage, missingMarkers, assemblyFlags), stageVars, missingMarkers);
        rawSections.add(new AssembledSection(12, "12 Prayer", sec12Body));

        // ---------------------------------------------------------------- Section 13 (internal)
        StringBuilder sec13 = new StringBuilder();
        sec13.append("<h3>Internal Partner Review Note</h3>");
        sec13.append("<p><strong>Notice:</strong> ").append(noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "N/A")
             .append(" | Stage template: ").append(normalizedStage).append(" | Section: ").append(noticeSec).append("</p>");
        sec13.append("<p><strong>Matched Issues & Templates:</strong></p><ul>");
        for (MatchedIssue issue : matchedIssues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            AiIssueContent c = ai.get(issue.issueNo());
            IssueMode mode = modeFor(issue, ft);
            sec13.append("<li>Issue #").append(issue.issueNo())
                  .append(": Match Status=").append(issue.status())
                  .append(", Cards=").append(issue.cardIds())
                  .append(", Template=").append(ft != null ? ft.templateId() : "N/A")
                  .append(" (v").append(ft != null ? ft.templateVersion() : 0).append(")")
                  .append(", Written by=").append(mode == IssueMode.TEMPLATE ? "template (code)"
                          : mode == IssueMode.MISSING_PERIOD ? "nothing (period missing)"
                          : (c != null ? "AI " + c.writer() : "AI writer pending"));
            if (mode == IssueMode.AI && c != null && ft != null && ft.blocks() != null && !ft.blocks().isEmpty()) {
                Set<String> used = anchoredBlocks.getOrDefault(issue.issueNo(), Set.of());
                sec13.append(", Template blocks used=").append(used.isEmpty() ? "none" : new TreeSet<>(used));
            }
            if (ft != null && ft.cardId() != null && ft.flags() != null && ft.flags().contains("no_template_available")) {
                sec13.append(", Note=No template available for card ").append(ft.cardId());
            }
            sec13.append("</li>");
        }
        sec13.append("</ul>");

        sec13.append("<p><strong>Procedural Objection Options (Partner to Select):</strong></p><ul>");
        Set<String> procOptions = new LinkedHashSet<>();
        for (FilledTemplateResult ft : templateMap.values()) {
            if (ft != null && ft.proceduralObjectionOptions() != null) procOptions.addAll(ft.proceduralObjectionOptions());
        }
        procOptions.addAll(proceduralOptionsForStage(normalizedStage, noticeSec));
        if (StageDetector.ANTI_PROFITEERING.equals(normalizedStage) || StageDetector.GENERIC_SCN.equals(normalizedStage)) {
            // Section 73/74/75 adjudication points do not apply to a Section 171 proceeding, and are not
            // assumed for a notice under some other provision (e.g. Section 63 / 129)
            procOptions.removeIf(o -> ADJUDICATION_PROVISION.matcher(o).find());
        }
        for (String o : procOptions) sec13.append("<li>").append(o).append("</li>");
        sec13.append("</ul>");

        if (!notes.isEmpty()) {
            sec13.append("<p><strong>Stage 3 Notes:</strong></p><ul>");
            for (String n : notes) sec13.append("<li>").append(n).append("</li>");
            sec13.append("</ul>");
        }
        sec13.append("<!-- CHECK_RESULTS_PLACEHOLDER -->");
        rawSections.add(new AssembledSection(13, "13 Internal Partner Note", sec13.toString()));

        // ---------------------------------------------------------------- Section 14
        String formattedDueDate = formatDueDateStr(noticeInfo != null ? noticeInfo.replyDueDate() : null, receiptDate, missingMarkers);
        StringBuilder sec14 = new StringBuilder();
        sec14.append("<h3>Client Summary</h3>");
        sec14.append("<p><strong>Notice Type:</strong> ").append(formatFullNoticeType(noticeSec)).append("</p>");
        sec14.append("<p><strong>Notice Reference:</strong> ").append(noticeRef).append(" dated ").append(noticeDate).append("</p>");
        sec14.append("<p><strong>Total Amount Demanded:</strong> ").append(totalDemand).append("</p>");
        sec14.append("<p><strong>Reply Due Date:</strong> ").append(formattedDueDate).append("</p>");
        sec14.append("<p><strong>Documents Required from Client:</strong></p>");
        if (documents.isEmpty()) {
            sec14.append("<p>[[PARTNER: list the documents required from the client]]</p>");
        } else {
            sec14.append(FREE_TEXT_START).append("<ol>");
            for (String d : documents) sec14.append("<li>").append(d).append("</li>");
            sec14.append("</ol>").append(FREE_TEXT_END);
        }
        rawSections.add(new AssembledSection(14, "14 Client Summary", sec14.toString()));

        // ---------------------------------------------------------------- Section 15
        String sec15Body = fillStagePlaceholders(
                getStageSectionOrMissing(stageTpl, "15", normalizedStage, missingMarkers, assemblyFlags), stageVars, missingMarkers);
        rawSections.add(new AssembledSection(15, "15 Filing Checklist", sec15Body));

        // ---------------------------------------------------------------- Cross-refs + continuous paragraph numbering
        List<AssembledSection> finalSections = new ArrayList<>();
        int pNumber = 1;
        for (AssembledSection sec : rawSections) {
            String resolvedHtml = resolveCrossReferences(sec.bodyHtml(), blockIdToRefTag, issueNoToRefTag);
            StringBuilder numHtml = new StringBuilder();
            for (String part : resolvedHtml.split("(?=<p>|<li>|<h3>|<ol>|<ul>|<table>)")) {
                if (part.startsWith("<p>") || part.startsWith("<li>")) {
                    numHtml.append("<!-- para:").append(pNumber++).append(" -->");
                }
                numHtml.append(part);
            }
            finalSections.add(new AssembledSection(sec.num(), sec.title(), numHtml.toString()));
        }

        return new AssembledReplyResult(finalSections, finalSourceMap, assemblyFlags, missingMarkers, pendingAiMarkers);
    }

    private static final Pattern ADJUDICATION_PROVISION = Pattern.compile("(?i)\\b(?:75\\s*\\(\\s*4\\s*\\)|73\\s*\\(\\s*10\\s*\\)|74A?\\s*\\(\\s*10\\s*\\))");

    /**
     * Code-built procedural objection options for the partner note. Anti-profiteering (Section 171)
     * proceedings follow Rules 129-133, not the Section 73/74/75 adjudication provisions; a generic show
     * cause notice (provision other than 73/74/74A/171) gets neutral options only.
     */
    static List<String> proceduralOptionsForStage(String stage, String noticeSec) {
        List<String> out = new ArrayList<>();
        if (StageDetector.ANTI_PROFITEERING.equals(stage)) {
            out.add("Check whether a copy of the DGAP investigation report and the evidence relied upon was supplied, with an opportunity to file objections (Rule 133).");
            out.add("Check whether an opportunity of hearing was given before the order, if requested in writing (Rule 133(2)).");
            out.add("Verify the time limits for the DGAP investigation (Rule 129(6)) and for the order (Rule 133(1)).");
            return out;
        }
        if (StageDetector.GENERIC_SCN.equals(stage)) {
            out.add("Check whether an opportunity of personal hearing has been offered before any adverse order.");
            out.add("Verify the time limit, if any, prescribed for the proceeding under " + noticeSec + ".");
            out.add("Check whether the notice states the provision, the grounds and the computation of the amount proposed.");
            return out;
        }
        out.add("Check whether an opportunity of personal hearing has been offered (Section 75(4)).");
        if (noticeSec.matches(".*\\b73\\b.*")) {
            out.add("Verify the limitation period under Section 73(10).");
        } else if (noticeSec.matches(".*\\b74A?\\b.*")) {
            out.add("Verify the limitation period under Section 74(10).");
        }
        return out;
    }

    private static String freeText(String html) {
        return html == null || html.isBlank() ? "" : FREE_TEXT_START + html + FREE_TEXT_END;
    }

    /** One line per issue in Section 01: a long AI summary keeps its first sentence (Section 04 has the rest). */
    static String oneLineSummary(String summary) {
        String s = summary.trim();
        if (DraftTextCleanup.wordCount(s) <= SUMMARY_BULLET_MAX_WORDS) return s;
        Matcher m = Pattern.compile("^.+?[.;](?=\\s+[A-Z(]|\\s*$)", Pattern.DOTALL).matcher(s);
        return m.find() ? m.group() : s;
    }

    // ------------------------------------------------------------------ Section 04 / 06 issue bodies

    private void appendIssueBody(
            StringBuilder sb, int secNum, MatchedIssue issue, FilledTemplateResult ft, AiIssueContent c,
            Set<String> anchors, int[] sub, int[] globalParaNo, Map<String, String> blockIdToRefTag,
            List<SourceMapEntry> sourceMap, List<String> pendingAiMarkers, List<String> missingMarkers,
            List<String> flags, List<String> notes
    ) {
        switch (modeFor(issue, ft)) {
            case TEMPLATE -> {
                for (FilledBlock fb : ft.blocks()) {
                    if (fb.section() == secNum) {
                        appendTemplateBlock(sb, secNum, fb, ft, sub, globalParaNo, blockIdToRefTag, sourceMap);
                    }
                }
            }
            case MISSING_PERIOD -> {
                sb.append("<p>").append(secNum).append('.').append(sub[0]++).append(" [[MISSING: period]]</p>");
                addOnce(missingMarkers, "period");
                globalParaNo[0]++;
            }
            case AI -> {
                List<AiFragment> frags = new ArrayList<>();
                if (c != null && c.fragments() != null) {
                    for (AiFragment f : c.fragments()) {
                        if (f.section() == secNum && f.html() != null && !f.html().isBlank()) frags.add(f);
                    }
                }
                if (frags.isEmpty()) {
                    // Section 06 may legitimately have nothing extra when 04 was written; 04 never may.
                    if (secNum == 4 || c == null || c.fragments() == null || c.fragments().isEmpty()) {
                        sb.append("<p>").append(secNum).append('.').append(sub[0]++).append(' ').append(pendingMarker(issue.issueNo())).append("</p>");
                        addOnce(pendingAiMarkers, "PENDING_AI: Issue " + issue.issueNo());
                        addOnce(flags, "pending_ai_writer");
                        globalParaNo[0]++;
                    } else {
                        sb.append("<p>").append(secNum).append('.').append(sub[0]++)
                          .append(" The legal submissions on this issue are set out with the grounds in Section 04.</p>");
                        globalParaNo[0]++;
                    }
                    return;
                }

                List<FilledBlock> blocks = new ArrayList<>();
                if (ft != null && ft.blocks() != null) {
                    for (FilledBlock fb : ft.blocks()) if (fb.section() == secNum) blocks.add(fb);
                }
                Set<String> blockIds = new HashSet<>();
                blocks.forEach(b -> blockIds.add(b.blockId()));

                List<AiFragment> unanchored = new ArrayList<>();
                for (AiFragment f : frags) {
                    if (f.afterBlock() == null || !blockIds.contains(f.afterBlock())) {
                        if (f.afterBlock() != null) {
                            notes.add("Issue #" + issue.issueNo() + ": AI text anchored to unknown block " + f.afterBlock()
                                    + " for section " + secNum + "; placed at the end of the issue.");
                        }
                        unanchored.add(f);
                    }
                }
                for (FilledBlock fb : blocks) {
                    if (anchors != null && anchors.contains(fb.blockId())) {
                        appendTemplateBlock(sb, secNum, fb, ft, sub, globalParaNo, blockIdToRefTag, sourceMap);
                        for (AiFragment f : frags) {
                            if (fb.blockId().equals(f.afterBlock())) {
                                appendAiHtml(sb, secNum, f.html(), issue.issueNo(), c.writer(), sub, globalParaNo, sourceMap);
                            }
                        }
                    }
                }
                for (AiFragment f : unanchored) {
                    appendAiHtml(sb, secNum, f.html(), issue.issueNo(), c.writer(), sub, globalParaNo, sourceMap);
                }
            }
        }
    }

    private void appendTemplateBlock(StringBuilder sb, int secNum, FilledBlock fb, FilledTemplateResult ft, int[] sub,
                                     int[] globalParaNo, Map<String, String> blockIdToRefTag, List<SourceMapEntry> sourceMap) {
        String pNum = secNum + "." + sub[0]++;
        blockIdToRefTag.put(fb.blockId(), pNum);
        String bodyText = fb.filledHtml().replaceAll("^<p>|</p>$", "");
        sb.append("<p>").append(pNum).append(' ').append(bodyText).append("</p>");
        sourceMap.add(new SourceMapEntry(globalParaNo[0]++, secNum, ft.templateId(), ft.templateVersion(), fb.blockId()));
    }

    /** Numbers every top-level &lt;p&gt; of the AI fragment (4.n / 6.n) and records it in the source map. */
    private void appendAiHtml(StringBuilder sb, int secNum, String html, int issueNo, String writer, int[] sub,
                              int[] globalParaNo, List<SourceMapEntry> sourceMap) {
        String body = html.trim();
        if (!body.contains("<p>")) {
            body = "<p>" + body + "</p>";
        }
        Matcher m = Pattern.compile("<p>").matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int k = sub[0]++;
            m.appendReplacement(out, "<p>" + secNum + "." + k + " ");
            sourceMap.add(new SourceMapEntry(globalParaNo[0]++, secNum, "AI:" + writer, 0,
                    "AI-ISSUE-" + issueNo + "-S" + secNum + "-P" + k));
        }
        m.appendTail(out);
        sb.append(out);
    }

    // ------------------------------------------------------------------ Section 05

    /**
     * Section 05. The paragraph count is the largest of: the matcher's para_map (category form
     * {"issue": [2, 3]} or paragraph form {"3": [1]}), the numbered paragraphs found in the notice text,
     * and the paragraphs the AI writers replied to. AI replies are never dropped: a reply to a paragraph
     * beyond what the matcher and the notice text show is printed and raises a warn flag.
     */
    private String buildParaWiseReply(MatchingResult matchingResult, DraftingAgent.DraftingInput input,
                                      List<MatchedIssue> issues, Map<Integer, AiIssueContent> ai,
                                      List<String> missingMarkers, List<String> flags, List<String> notes) {
        Map<String, List<Integer>> paraMap = matchingResult != null && matchingResult.paraMap() != null ? matchingResult.paraMap() : Map.of();

        Map<String, Set<Integer>> categories = new HashMap<>();
        Map<Integer, Set<Integer>> paraToIssues = new TreeMap<>();
        int matcherMax = 0;
        for (Map.Entry<String, List<Integer>> e : paraMap.entrySet()) {
            String key = e.getKey() != null ? e.getKey().trim().toLowerCase() : "";
            List<Integer> values = e.getValue() != null ? e.getValue() : List.of();
            if (PARA_CATEGORIES.contains(key)) {
                for (Integer v : values) {
                    if (v == null || v < 1) continue;
                    categories.computeIfAbsent(key, k -> new HashSet<>()).add(v);
                    matcherMax = Math.max(matcherMax, v);
                }
            } else {
                Matcher km = PARA_KEY.matcher(key);
                if (km.find()) {
                    int p = Integer.parseInt(km.group(1));
                    if (p < 1) continue;
                    Set<Integer> linked = paraToIssues.computeIfAbsent(p, k -> new TreeSet<>());
                    for (Integer v : values) if (v != null) linked.add(v);
                    matcherMax = Math.max(matcherMax, p);
                }
            }
        }
        int noticeParas = NoticeParagraphs.count(input != null ? input.noticeOcrExcerpt() : null);

        // AI para replies, keyed by notice paragraph number
        Map<Integer, List<ParaReply>> aiParaReplies = new TreeMap<>();
        for (MatchedIssue issue : issues) {
            AiIssueContent c = ai.get(issue.issueNo());
            if (c == null || c.fragments() == null) continue;
            for (AiFragment f : c.fragments()) {
                if (f.section() != 5 || f.html() == null) continue;
                Matcher m = PARA_LI.matcher(f.html());
                boolean any = false;
                while (m.find()) {
                    any = true;
                    int p = Integer.parseInt(m.group(1));
                    String text = m.group(3).trim();
                    if (p >= 1 && !text.isEmpty()) {
                        aiParaReplies.computeIfAbsent(p, k -> new ArrayList<>()).add(new ParaReply(subParaLabel(m.group(2)), text));
                    }
                }
                if (!any) {
                    notes.add("Issue #" + issue.issueNo() + ": AI para-wise text was not in 'Para N' form and was not used.");
                }
            }
        }
        int aiMax = aiParaReplies.isEmpty() ? 0 : ((TreeMap<Integer, List<ParaReply>>) aiParaReplies).lastKey();

        int known = Math.max(matcherMax, noticeParas);
        int paraCount = Math.max(known, aiMax);
        if (paraCount == 0) {
            addOnce(missingMarkers, "para_map");
            return "<p>[[MISSING: para_map]]</p>";
        }
        if (aiMax > known) {
            addOnce(flags, "warn:ai_para_beyond_notice");
            notes.add("AI para-wise replies go up to Para " + aiMax + " but the matcher para_map shows " + matcherMax
                    + " and the notice text " + noticeParas + " numbered paragraphs; all AI replies printed, check the numbering.");
        }

        Set<Integer> recordParas = categories.getOrDefault("record", Set.of());
        Set<Integer> issueParas = categories.getOrDefault("issue", Set.of());
        Set<Integer> demandParas = categories.getOrDefault("demand", Set.of());
        Set<Integer> directionParas = categories.getOrDefault("directions", Set.of());

        StringBuilder sb = new StringBuilder();
        sb.append("<p>Point-by-point reply to the paragraphs of the notice:</p><ol>");
        for (int p = 1; p <= paraCount; p++) {
            sb.append("<li><strong>Para ").append(p).append(":</strong> ");
            if (aiParaReplies.containsKey(p)) {
                sb.append(mergeParaReplies(aiParaReplies.get(p)));
            } else if (paraToIssues.containsKey(p)) {
                sb.append(denialWithRefs(issuesByNumber(paraToIssues.get(p), p, issues)));
            } else if (issueParas.contains(p)) {
                sb.append(denialWithRefs(issuesForPara(p, issues)));
            } else if (demandParas.contains(p)) {
                sb.append("Denied. The proposals in this paragraph are not sustainable for the reasons set out in Sections 04 and 06.");
            } else if (directionParas.contains(p)) {
                sb.append("Noted. This reply is submitted in compliance with the direction.");
            } else if (recordParas.contains(p)) {
                sb.append("Matter of record; no comments.");
            } else {
                sb.append("[[PARTNER: reply to Para ").append(p).append(" (paragraph not classified)]]");
            }
            sb.append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    /** One AI reply to a notice paragraph; {@code subPara} is "(c)" / ".1", or "" for the whole paragraph. */
    record ParaReply(String subPara, String text) {}

    private static String subParaLabel(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Merges the replies of several issues to one notice paragraph: whole-paragraph replies first, then
     * sub-paragraph replies in sub-para order ((a), (b), (c) / .1, .2), each starting as its own sentence
     * ("Sub-para (c): ..."). A sentence already given for this paragraph is not repeated.
     */
    static String mergeParaReplies(List<ParaReply> replies) {
        List<ParaReply> ordered = new ArrayList<>();
        for (ParaReply r : replies) if (r.subPara().isEmpty()) ordered.add(r);
        List<ParaReply> labelled = new ArrayList<>();
        for (ParaReply r : replies) if (!r.subPara().isEmpty()) labelled.add(r);
        labelled.sort(Comparator.comparing(ParaReply::subPara, ReplyAssemblyService::compareSubPara));   // stable
        ordered.addAll(labelled);

        Set<String> seen = new HashSet<>();
        List<String> parts = new ArrayList<>();
        Map<String, Integer> labelPart = new HashMap<>();
        for (ParaReply r : ordered) {
            StringBuilder kept = new StringBuilder();
            for (String sentence : r.text().split("(?<=[.;!?])\\s+(?=[A-Z(\\[<])")) {
                String key = sentence.replaceAll("<[^>]+>", " ").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
                if (key.isEmpty() || !seen.add(key)) continue;
                if (kept.length() > 0) kept.append(' ');
                kept.append(sentence.trim());
            }
            if (kept.length() == 0) continue;
            String body = endSentence(kept.toString());
            if (r.subPara().isEmpty()) {
                parts.add(body);
            } else if (labelPart.containsKey(r.subPara())) {      // two issues on the same sub-para: one entry
                int i = labelPart.get(r.subPara());
                parts.set(i, parts.get(i) + " " + body);
            } else {
                labelPart.put(r.subPara(), parts.size());
                parts.add("Sub-para " + r.subPara() + ": " + body);
            }
        }
        return String.join(" ", parts);
    }

    private static int compareSubPara(String a, String b) {
        String na = a.replaceAll("[^a-z0-9]", "");
        String nb = b.replaceAll("[^a-z0-9]", "");
        if (na.matches("\\d+") && nb.matches("\\d+")) return Integer.compare(Integer.parseInt(na), Integer.parseInt(nb));
        return na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
    }

    private static String endSentence(String s) {
        String t = s.trim();
        String plain = t.replaceAll("(?:</[a-z]+>\\s*)+$", "");
        return plain.matches("(?s).*[.;:!?)\\]]$") ? t : t + ".";
    }

    /** The matched issues with the given numbers; falls back to {@link #issuesForPara} when none match. */
    private static List<MatchedIssue> issuesByNumber(Set<Integer> issueNos, int p, List<MatchedIssue> issues) {
        List<MatchedIssue> hits = new ArrayList<>();
        for (MatchedIssue issue : issues) {
            if (issueNos.contains(issue.issueNo())) hits.add(issue);
        }
        return hits.isEmpty() ? issuesForPara(p, issues) : hits;
    }

    /** Issues whose notice paragraphs include {@code p}; all issues when Stage 1 did not link paragraphs. */
    private static List<MatchedIssue> issuesForPara(int p, List<MatchedIssue> issues) {
        List<MatchedIssue> hits = new ArrayList<>();
        for (MatchedIssue issue : issues) {
            if (issue.noticeParas() != null && issue.noticeParas().stream().anyMatch(ref -> refersToPara(ref, p))) {
                hits.add(issue);
            }
        }
        return hits.isEmpty() ? issues : hits;
    }

    private static boolean refersToPara(String ref, int p) {
        Matcher m = Pattern.compile("\\d+").matcher(ref != null ? ref : "");
        while (m.find()) {
            if (Integer.parseInt(m.group()) == p) return true;
        }
        return false;
    }

    private static String denialWithRefs(List<MatchedIssue> issues) {
        if (issues.isEmpty()) {
            return "Denied, for the reasons stated in Section 04.";
        }
        if (issues.size() == 1) {
            int n = issues.get(0).issueNo();
            return "Denied. Please refer to the reply to Issue #" + n + " at para {{ref:ISSUE-" + n + "}}.";
        }
        List<String> labels = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (MatchedIssue i : issues) {
            labels.add("#" + i.issueNo());
            refs.add("{{ref:ISSUE-" + i.issueNo() + "}}");
        }
        return "Denied, for the reasons stated in reply to Issues " + joinAnd(labels) + " at paras " + joinAnd(refs) + ".";
    }

    // ------------------------------------------------------------------ Documents

    private static List<String> collectDocuments(List<MatchedIssue> issues, Map<Integer, FilledTemplateResult> templateMap,
                                                 Map<Integer, AiIssueContent> ai, Map<Integer, Set<String>> anchoredBlocks) {
        LinkedHashMap<String, String> docs = new LinkedHashMap<>();
        for (MatchedIssue issue : issues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            AiIssueContent c = ai.get(issue.issueNo());
            IssueMode mode = modeFor(issue, ft);
            boolean templateUsed = mode == IssueMode.TEMPLATE
                    || (mode == IssueMode.AI && !anchoredBlocks.getOrDefault(issue.issueNo(), Set.of()).isEmpty());
            if (templateUsed && ft != null && ft.documents() != null) {
                for (String d : ft.documents()) docs.putIfAbsent(d.trim().toLowerCase(), d.trim());
            }
            if (mode == IssueMode.AI && c != null && c.documents() != null) {
                for (String d : c.documents()) docs.putIfAbsent(d.trim().toLowerCase(), d.trim());
            }
        }
        // the same document listed by several issues (or worded slightly differently) is listed once
        return DraftTextCleanup.dedupeDocuments(new ArrayList<>(docs.values()));
    }

    // ------------------------------------------------------------------ Helpers

    /** The provision the notice is issued under, from the notice record; never a stage default. */
    public static String noticeSection(DraftingAgent.DraftingInput input) {
        Object sec = input != null && input.notice() != null ? input.notice().get("section") : null;
        if (sec == null || sec.toString().isBlank()) {
            return "[[MISSING: notice_section]]";
        }
        String s = sec.toString().trim();
        return s.matches("\\d+[A-Z]?(\\(.*)?") ? "Section " + s : s;
    }

    private static String lawName(DraftingAgent.DraftingInput input) {
        String law = input != null ? input.law() : null;
        if (law == null || law.isBlank()) return "CGST Act, 2017";
        return law.matches(".*\\b(19|20)\\d{2}\\b.*") ? law.trim() : law.trim() + ", 2017";
    }

    private static String issueFactsLabel(MatchedIssue issue) {
        if (issue.facts() == null) return "";
        List<String> parts = new ArrayList<>();
        Object legal = issue.facts().get("legal_section");
        if (legal != null && !legal.toString().isBlank()) parts.add(legal.toString().trim());
        Object amt = issue.facts().get("amount");
        if (amt != null) {
            try {
                parts.add("amount involved: " + TemplateFillService.formatIndianCurrency(new BigDecimal(amt.toString())));
            } catch (NumberFormatException ignored) {
                // non-numeric amount from extraction: leave it out rather than print raw text
            }
        }
        return String.join("; ", parts);
    }

    /** Tax period: matcher notice block, then the notice record, then the issues' extracted periods. */
    private static String taxPeriod(MatchedNoticeInfo noticeInfo, DraftingAgent.DraftingInput input, List<MatchedIssue> issues) {
        if (noticeInfo != null && noticeInfo.taxPeriod() != null && !noticeInfo.taxPeriod().isBlank()
                && !"null".equalsIgnoreCase(noticeInfo.taxPeriod().trim())) {
            return noticeInfo.taxPeriod().trim();
        }
        Object fromNotice = input != null && input.notice() != null ? input.notice().get("tax_period") : null;
        if (fromNotice != null && !fromNotice.toString().isBlank()) return fromNotice.toString().trim();
        Set<String> periods = new LinkedHashSet<>();
        for (MatchedIssue issue : issues) {
            Object p = issue.facts() != null ? issue.facts().get("period") : null;
            if (p != null && !p.toString().isBlank() && !"null".equalsIgnoreCase(p.toString().trim())) periods.add(p.toString().trim());
        }
        return periods.isEmpty() ? null : String.join(", ", periods);
    }

    /** Date the client received the notice: from the notice record (intake/triage) or the issue facts; never inferred. */
    private static String dateOfReceipt(DraftingAgent.DraftingInput input, List<MatchedIssue> issues) {
        Map<String, Object> notice = input != null && input.notice() != null ? input.notice() : Map.of();
        for (String key : List.of("date_of_receipt", "receipt_date")) {
            Object v = notice.get(key);
            if (v != null && !v.toString().isBlank()) return formatDateStr(v.toString().trim());
        }
        for (MatchedIssue issue : issues) {
            Object v = issue.facts() != null ? issue.facts().get("date_of_receipt") : null;
            if (v != null && !v.toString().isBlank() && !"null".equalsIgnoreCase(v.toString().trim())) {
                return formatDateStr(v.toString().trim());
            }
        }
        return null;
    }

    /**
     * State of registration: the registration record's name unless it is blank or a placeholder such as
     * "Unknown State", else the name for the GSTIN's state code. Null when neither gives a real name.
     */
    static String registrationState(DraftingAgent.DraftingInput input) {
        if (input == null) return null;
        String recorded = input.registrationStateName();
        if (!GstStateCodes.isPlaceholderName(recorded)) return recorded.trim();
        return GstStateCodes.nameForGstin(input.registrationIdentifier()).orElse(null);
    }

    private static String orMissing(String value, String field, List<String> missingMarkers, List<String> flags) {
        if (value != null && !value.isBlank()) return value;
        addOnce(missingMarkers, field);
        addOnce(flags, "warn:missing_" + field);
        return "[[MISSING: " + field + "]]";
    }

    private static String pendingMarker(int issueNo) {
        return "[[PENDING_AI: Issue " + issueNo + " — partial/new, written in Stage 3]]";
    }

    private static String nonBlank(String s, String fallback) {
        return s != null && !s.isBlank() ? s : fallback;
    }

    private static void addOnce(List<String> list, String v) {
        if (!list.contains(v)) list.add(v);
    }

    private static String joinAnd(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /** Fills {{notice.section}}-style placeholders in stage templates; unknown names become [[MISSING: name]]. */
    private static String fillStagePlaceholders(String html, Map<String, String> vars, List<String> missingMarkers) {
        if (html == null) return "";
        Matcher m = STAGE_PLACEHOLDER.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = vars.get(key);
            if (val == null || val.isBlank()) {
                addOnce(missingMarkers, key);
                val = "[[MISSING: " + key + "]]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return TemplateFillService.collapseDuplicateCurrency(sb.toString());
    }

    private String getStageSectionOrMissing(
            StageTemplate stageTpl,
            String secKey,
            String stage,
            List<String> missingMarkers,
            List<String> flags
    ) {
        if (stageTpl != null && stageTpl.sections() != null && stageTpl.sections().containsKey(secKey)) {
            String val = stageTpl.sections().get(secKey);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        // no fallback to another proceeding's template: placeholder + block flag
        String missingText = "[[MISSING: stage template for " + stage + "]]";
        addOnce(missingMarkers, missingText);
        addOnce(flags, "missing_stage_template_" + stage);
        addOnce(flags, "block:stage_template_missing");
        return "<p>" + missingText + "</p>";
    }

    private String resolveCrossReferences(
            String html,
            Map<String, String> blockIdToRef,
            Map<Integer, String> issueNoToRef
    ) {
        if (html == null) return "";

        Pattern pattern = Pattern.compile("\\{\\{ref:([^}]+)\\}\\}");
        Matcher matcher = pattern.matcher(html);

        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String refKey = matcher.group(1).trim();
            String refValue = "para [ref]";

            if (blockIdToRef.containsKey(refKey)) {
                refValue = blockIdToRef.get(refKey);
            } else if (refKey.startsWith("ISSUE-")) {
                try {
                    int issueNo = Integer.parseInt(refKey.substring(6));
                    if (issueNoToRef.containsKey(issueNo)) {
                        refValue = issueNoToRef.get(issueNo);
                    }
                } catch (NumberFormatException ignored) {}
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(refValue));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public StageTemplate loadStageTemplate(String stage) {
        if (inMemoryStageTemplates.containsKey(stage)) {
            return inMemoryStageTemplates.get(stage);
        }

        if (jdbcTemplate != null) {
            String sql = """
                    SELECT stage, version, effective_from, effective_to, status, sections, approved_by, approved_at, created_at
                    FROM stage_templates
                    WHERE stage = ? AND status IN ('active', 'draft')
                    ORDER BY version DESC
                    LIMIT 1
                    """;
            try {
                List<StageTemplate> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
                    String st = rs.getString("stage");
                    int ver = rs.getInt("version");
                    LocalDate effFrom = rs.getDate("effective_from") != null ? rs.getDate("effective_from").toLocalDate() : null;
                    LocalDate effTo = rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null;
                    String stat = rs.getString("status");
                    Map<String, String> secMap = parseSectionsJson(rs.getString("sections"));
                    String appBy = rs.getString("approved_by");
                    OffsetDateTime appAt = rs.getObject("approved_at", OffsetDateTime.class);
                    OffsetDateTime crAt = rs.getObject("created_at", OffsetDateTime.class);

                    return new StageTemplate(st, ver, effFrom, effTo, stat, secMap, appBy, appAt, crAt);
                }, stage);

                if (!list.isEmpty()) {
                    return list.get(0);
                }
            } catch (Exception e) {
                log.warn("DB query for stage_templates failed: {}", e.getMessage());
            }
        }
        return null;
    }

    static String formatFullNoticeType(String noticeSec) {
        if (noticeSec == null || noticeSec.isBlank()) return "[[MISSING: notice_section]]";
        if (noticeSec.startsWith("[[") || noticeSec.startsWith("Show Cause Notice") || noticeSec.startsWith("Intimation of Discrepancy")) {
            return noticeSec;
        }
        if (noticeSec.matches(".*\\b7[34]A?\\b.*")) {
            return "Show Cause Notice under " + (noticeSec.startsWith("Section") ? noticeSec : "Section " + noticeSec);
        }
        if (noticeSec.contains("ASMT")) {
            return "Intimation of Discrepancy under " + noticeSec;
        }
        return "Notice under " + noticeSec;
    }

    private String formatDueDateStr(String dateStr, String receiptDate, List<String> missingMarkers) {
        if (dateStr == null || dateStr.isBlank()) {
            addOnce(missingMarkers, "reply_due_date");
            return "[[MISSING: reply_due_date]]";
        }
        String trimmed = dateStr.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*") || trimmed.matches("\\d{2}-\\d{2}-\\d{4}")) {
            return formatDateStr(trimmed);
        }
        // Keep the notice's own trigger event ("from service", "from receipt"); default to receipt
        Matcher days = Pattern.compile("(?i)(\\d+)\\s*days?(?:\\s+(?:from|of)\\s+(?:the\\s+)?(?:date\\s+of\\s+)?(service|receipt|issue))?").matcher(trimmed);
        if (days.find()) {
            String event = days.group(2) != null ? days.group(2).toLowerCase() : "receipt";
            if ("receipt".equals(event) && receiptDate != null) {
                return "Within " + days.group(1) + " days from receipt (date of receipt: " + receiptDate + ")";
            }
            addOnce(missingMarkers, "date_of_" + event);
            return "Within " + days.group(1) + " days from " + event + " (date of " + event + ": [[MISSING: date_of_" + event + "]])";
        }
        addOnce(missingMarkers, "reply_due_date");
        return "[[MISSING: reply_due_date]]";
    }

    private static String formatClientName(String name) {
        if (name == null || name.isBlank()) return "[[MISSING: client_legal_name]]";
        if (name.startsWith("M/s ") || name.startsWith("M/S ")) return name;
        return "M/s " + name;
    }

    private static String formatDateStr(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return "[[MISSING: issue_date]]";
        if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                LocalDate ld = LocalDate.parse(dateStr.substring(0, 10));
                return ld.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignored) {}
        }
        return dateStr;
    }

    private Map<String, String> parseSectionsJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
