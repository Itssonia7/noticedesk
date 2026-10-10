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

    private static final Pattern PARA_LI = Pattern.compile(
            "<li>\\s*(?:<(?:strong|b)>)?\\s*Para(?:graph)?\\s*(\\d+)\\s*[:.]?\\s*(?:</(?:strong|b)>)?\\s*[:.\\-–—]?\\s*(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STAGE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_.]+)\\s*\\}\\}");

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

    /** Stage 3 output for one issue. {@code writer} is "partial" or "new_issue" (internal only). */
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

        String normalizedStage = stageName != null ? stageName.toLowerCase().trim() : "scn_73";
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
        if (highStakes != null && highStakes.section01Html() != null && !highStakes.section01Html().isBlank()) {
            sec01.append(highStakes.section01Html());
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
                        sec01.append(c.summaryLine());
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
        String state = input != null && input.registrationStateName() != null && !input.registrationStateName().isBlank()
                ? input.registrationStateName() : "[[MISSING: registration_state]]";
        if (state.startsWith("[[MISSING")) addOnce(missingMarkers, "registration_state");
        sec03.append("<p>").append(clientName).append(" (GSTIN: ").append(gstin)
             .append(") is registered under the GST law in the State of ").append(state).append(".</p>");
        sec03.append("<p>Notice reference ").append(noticeRef).append(" dated ").append(noticeDate)
             .append(" pertains to Financial Year ").append(fy).append(".</p>");
        if (highStakes != null && highStakes.section03Html() != null && !highStakes.section03Html().isBlank()) {
            sec03.append(highStakes.section03Html());
        }
        rawSections.add(new AssembledSection(3, "03 Factual Background", sec03.toString()));

        rawSections.add(new AssembledSection(4, "04 Issue-wise Response", sec04.toString()));

        // ---------------------------------------------------------------- Section 05
        rawSections.add(new AssembledSection(5, "05 Para-wise Reply",
                buildParaWiseReply(matchingResult, input, matchedIssues, ai, missingMarkers, notes)));

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
        procOptions.add("Check whether an opportunity of personal hearing has been offered (Section 75(4)).");
        if (noticeSec.matches(".*\\b73\\b.*")) {
            procOptions.add("Verify the limitation period under Section 73(10).");
        } else if (noticeSec.matches(".*\\b74A?\\b.*")) {
            procOptions.add("Verify the limitation period under Section 74(10).");
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
        String formattedDueDate = formatDueDateStr(noticeInfo != null ? noticeInfo.replyDueDate() : null, missingMarkers);
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
            sec14.append("<ol>");
            for (String d : documents) sec14.append("<li>").append(d).append("</li>");
            sec14.append("</ol>");
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

    private String buildParaWiseReply(MatchingResult matchingResult, DraftingAgent.DraftingInput input,
                                      List<MatchedIssue> issues, Map<Integer, AiIssueContent> ai,
                                      List<String> missingMarkers, List<String> notes) {
        Map<String, List<Integer>> paraMap = matchingResult != null && matchingResult.paraMap() != null ? matchingResult.paraMap() : Map.of();

        int paraCount = 0;
        for (List<Integer> list : paraMap.values()) {
            for (Integer v : list) if (v != null && v > paraCount) paraCount = v;
        }
        if (paraCount == 0) {
            addOnce(missingMarkers, "para_map");
            return "<p>[[MISSING: para_map]]</p>";
        }

        // AI para replies, keyed by notice paragraph number (only paragraphs that exist in THIS notice)
        Map<Integer, List<String>> aiParaReplies = new TreeMap<>();
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
                    String text = m.group(2).trim();
                    if (p < 1 || p > paraCount) {
                        notes.add("Issue #" + issue.issueNo() + ": AI para-wise reply for Para " + p
                                + " ignored (the notice has " + paraCount + " paragraphs).");
                    } else if (!text.isEmpty()) {
                        aiParaReplies.computeIfAbsent(p, k -> new ArrayList<>()).add(text);
                    }
                }
                if (!any) {
                    notes.add("Issue #" + issue.issueNo() + ": AI para-wise text was not in 'Para N' form and was not used.");
                }
            }
        }

        List<Integer> recordParas = paraMap.getOrDefault("record", List.of());
        List<Integer> issueParas = paraMap.getOrDefault("issue", List.of());
        List<Integer> demandParas = paraMap.getOrDefault("demand", List.of());
        List<Integer> directionParas = paraMap.getOrDefault("directions", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("<p>Point-by-point reply to the paragraphs of the notice:</p><ol>");
        for (int p = 1; p <= paraCount; p++) {
            sb.append("<li><strong>Para ").append(p).append(":</strong> ");
            if (aiParaReplies.containsKey(p)) {
                sb.append(String.join(" ", aiParaReplies.get(p)));
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
        return new ArrayList<>(docs.values());
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
        return sb.toString();
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
        String missingText = "[[MISSING: stage template for " + stage + "]]";
        addOnce(missingMarkers, missingText);
        addOnce(flags, "missing_stage_template_" + stage);
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

    private String formatDueDateStr(String dateStr, List<String> missingMarkers) {
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
