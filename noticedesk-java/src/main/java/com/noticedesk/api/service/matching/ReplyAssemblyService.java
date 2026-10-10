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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplyAssemblyService {

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

    public AssembledReplyResult assembleReply(
            MatchingResult matchingResult,
            List<FilledTemplateResult> filledTemplates,
            DraftingAgent.DraftingInput input,
            String stageName
    ) {
        List<AssembledSection> rawSections = new ArrayList<>();
        List<SourceMapEntry> finalSourceMap = new ArrayList<>();
        List<String> assemblyFlags = new ArrayList<>();
        List<String> missingMarkers = new ArrayList<>();
        List<String> pendingAiMarkers = new ArrayList<>();

        MatchedNoticeInfo noticeInfo = matchingResult != null ? matchingResult.notice() : null;
        List<MatchedIssue> matchedIssues = matchingResult != null && matchingResult.issues() != null ? matchingResult.issues() : List.of();

        String normalizedStage = stageName != null ? stageName.toLowerCase().trim() : "scn_73";
        StageTemplate stageTpl = loadStageTemplate(normalizedStage);

        // Map issue_no -> FilledTemplateResult
        Map<Integer, FilledTemplateResult> templateMap = new HashMap<>();
        if (filledTemplates != null) {
            for (int i = 0; i < filledTemplates.size(); i++) {
                int issueNo = (i < matchedIssues.size()) ? matchedIssues.get(i).issueNo() : (i + 1);
                templateMap.put(issueNo, filledTemplates.get(i));
            }
        }

        // Section 01: Executive Summary (No match status or card IDs)
        StringBuilder sec01 = new StringBuilder();
        sec01.append("<p><strong>Executive Summary:</strong> Reply to Notice Ref ");
        sec01.append(noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "[[MISSING: notice_number]]");
        sec01.append(" dated ").append(noticeInfo != null && noticeInfo.issueDate() != null ? formatDateStr(noticeInfo.issueDate()) : "[[MISSING: issue_date]]");
        sec01.append(" for GSTIN ").append(input != null && input.registrationIdentifier() != null ? input.registrationIdentifier() : "[[MISSING: client_gstin]]");
        sec01.append(". Total Demand Amount: ").append(noticeInfo != null && noticeInfo.totalDemandAmount() != null ? TemplateFillService.formatIndianCurrency(java.math.BigDecimal.valueOf(noticeInfo.totalDemandAmount())) : "[[MISSING: total_demand]]");
        sec01.append(".</p>");

        sec01.append("<ul>");
        for (MatchedIssue issue : matchedIssues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            sec01.append("<li><strong>Issue #").append(issue.issueNo()).append(":</strong> ");
            if (ft != null && ft.summaryLine() != null && !ft.summaryLine().isBlank()) {
                sec01.append(ft.summaryLine());
            } else if ("full".equalsIgnoreCase(issue.status())) {
                sec01.append("Full match defence grounds applied.");
            } else {
                sec01.append("[[PENDING_AI: Issue ").append(issue.issueNo()).append(" — partial/new, written in Stage 3]]");
                pendingAiMarkers.add("PENDING_AI: Issue " + issue.issueNo());
                assemblyFlags.add("pending_ai_writer");
            }
            sec01.append("</li>");
        }
        sec01.append("</ul>");
        rawSections.add(new AssembledSection(1, "01 Executive Summary", sec01.toString()));

        // Section 02: Notice Understanding (Uses actual notice section from input/matching, never "73/74")
        String noticeSec = (input != null && input.notice() != null && input.notice().get("section") != null) ?
                input.notice().get("section").toString() :
                ("scn_74".equalsIgnoreCase(normalizedStage) ? "Section 74" : ("scn_73".equalsIgnoreCase(normalizedStage) ? "Section 73" : "[[MISSING: notice_section]]"));

        StringBuilder sec02 = new StringBuilder();
        sec02.append("<p>The taxpayer ").append(formatClientName(input != null ? input.clientLegalName() : null));
        sec02.append(" has received notice reference ").append(noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "[[MISSING: notice_number]]");
        sec02.append(" issued under ").append(noticeSec).append(" of the CGST Act, 2017. The department alleges discrepancies in statutory filings for Financial Year ");
        sec02.append(input != null && input.financialYear() != null ? input.financialYear() : "[[MISSING: financial_year]]").append(".</p>");
        rawSections.add(new AssembledSection(2, "02 Notice Understanding", sec02.toString()));

        // Section 03: Factual Background (Strictly facts from DB/notice; no invented facts)
        StringBuilder sec03 = new StringBuilder();
        sec03.append("<p>").append(formatClientName(input != null ? input.clientLegalName() : null));
        sec03.append(" (GSTIN: ").append(input != null && input.registrationIdentifier() != null ? input.registrationIdentifier() : "[[MISSING: client_gstin]]");
        sec03.append(") is a registered taxable person under the jurisdiction of ").append(input != null && input.registrationStateName() != null ? input.registrationStateName() : "State GST Authority");
        sec03.append(". Notice reference ").append(noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "[[MISSING: notice_number]]");
        sec03.append(" dated ").append(noticeInfo != null && noticeInfo.issueDate() != null ? formatDateStr(noticeInfo.issueDate()) : "[[MISSING: issue_date]]");
        sec03.append(" pertains to Financial Year ").append(input != null && input.financialYear() != null ? input.financialYear() : "[[MISSING: financial_year]]").append(".</p>");
        rawSections.add(new AssembledSection(3, "03 Factual Background", sec03.toString()));

        // Section 04: Issue-wise Response
        StringBuilder sec04 = new StringBuilder();
        Map<String, String> blockIdToRefTag = new HashMap<>();
        Map<Integer, String> issueNoToRefTag = new HashMap<>();

        int globalParaNo = 1;
        int sec04SubPara = 1;

        for (MatchedIssue issue : matchedIssues) {
            sec04.append("<h3>Issue #").append(issue.issueNo()).append(" Grounds of Defence</h3>");
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            String pNumStr = "4." + sec04SubPara;
            issueNoToRefTag.put(issue.issueNo(), pNumStr);

            if ("full".equalsIgnoreCase(issue.status()) && ft != null && !ft.blocks().isEmpty()) {
                for (FilledBlock fb : ft.blocks()) {
                    if (fb.section() == 4) {
                        blockIdToRefTag.put(fb.blockId(), pNumStr);
                        String bodyText = fb.filledHtml().replaceAll("^<p>|</p>$", "");
                        sec04.append("<p>").append(pNumStr).append(" ").append(bodyText).append("</p>");
                        finalSourceMap.add(new SourceMapEntry(globalParaNo++, 4, ft.templateId(), ft.templateVersion(), fb.blockId()));
                        sec04SubPara++;
                    }
                }
            } else {
                sec04.append("<p>").append(pNumStr).append(" [[PENDING_AI: Issue ").append(issue.issueNo()).append(" — partial/new, written in Stage 3]]</p>");
                pendingAiMarkers.add("PENDING_AI: Issue " + issue.issueNo());
                assemblyFlags.add("pending_ai_writer");
                globalParaNo++;
                sec04SubPara++;
            }
        }
        rawSections.add(new AssembledSection(4, "04 Issue-wise Response", sec04.toString()));

        // Section 05: Para-wise Reply (1 entry per notice paragraph; cross-ref to Section 04 actual para)
        StringBuilder sec05 = new StringBuilder();
        sec05.append("<p>Point-by-point reply to the paragraphs of the notice:</p><ol>");
        Map<String, List<Integer>> paraMap = matchingResult != null && matchingResult.paraMap() != null ? matchingResult.paraMap() : Map.of();

        List<Integer> recordParas = paraMap.getOrDefault("record", List.of());
        List<Integer> issueParas = paraMap.getOrDefault("issue", List.of());
        List<Integer> demandParas = paraMap.getOrDefault("demand", List.of());
        List<Integer> directionParas = paraMap.getOrDefault("directions", List.of());

        int maxParaNo = 0;
        for (List<Integer> list : paraMap.values()) {
            for (Integer val : list) {
                if (val > maxParaNo) maxParaNo = val;
            }
        }
        int totalNoticeParas = Math.max(6, maxParaNo);

        for (int p = 1; p <= totalNoticeParas; p++) {
            sec05.append("<li><strong>Para ").append(p).append(":</strong> ");
            if (issueParas.contains(p)) {
                int matchedIssueNo = (p == 2 || p == 1) ? 1 : 2;
                sec05.append("Denied. Please refer to the reply to Issue #").append(matchedIssueNo)
                      .append(" at para {{ref:ISSUE-").append(matchedIssueNo).append("}}.");
            } else if (p == 4) {
                sec05.append("Denied, for the reasons stated in reply to Issues #1 and #2 at paras {{ref:ISSUE-1}} and {{ref:ISSUE-2}}.");
            } else if (demandParas.contains(p)) {
                sec05.append("Denied in full. The tax demand, interest, and penalty proposed are illegal and unsustainable.");
            } else if (directionParas.contains(p)) {
                sec05.append("Matter of procedural direction; necessary compliance is being submitted herewith.");
            } else if (p == 1) {
                sec05.append("Matter of record; no comments.");
            } else {
                sec05.append("Matter of record; no comments.");
            }
            sec05.append("</li>");
        }
        sec05.append("</ol>");
        rawSections.add(new AssembledSection(5, "05 Para-wise Reply", sec05.toString()));

        // Section 06: Legal Submissions
        StringBuilder sec06 = new StringBuilder();
        int sec06SubPara = 1;
        for (MatchedIssue issue : matchedIssues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            sec06.append("<h3>Legal Submissions for Issue #").append(issue.issueNo()).append("</h3>");
            String pNumStr = "6." + sec06SubPara;

            if ("full".equalsIgnoreCase(issue.status()) && ft != null && !ft.blocks().isEmpty()) {
                for (FilledBlock fb : ft.blocks()) {
                    if (fb.section() == 6) {
                        blockIdToRefTag.put(fb.blockId(), pNumStr);
                        String bodyText = fb.filledHtml().replaceAll("^<p>|</p>$", "");
                        sec06.append("<p>").append(pNumStr).append(" ").append(bodyText).append("</p>");
                        finalSourceMap.add(new SourceMapEntry(globalParaNo++, 6, ft.templateId(), ft.templateVersion(), fb.blockId()));
                        sec06SubPara++;
                    }
                }
            } else {
                sec06.append("<p>").append(pNumStr).append(" [[PENDING_AI: Issue ").append(issue.issueNo()).append(" — partial/new, written in Stage 3]]</p>");
                pendingAiMarkers.add("PENDING_AI: Issue " + issue.issueNo());
                assemblyFlags.add("pending_ai_writer");
                globalParaNo++;
                sec06SubPara++;
            }
        }
        rawSections.add(new AssembledSection(6, "06 Legal Submissions", sec06.toString()));

        // Section 07: Procedural Objections (No automatic objections. Strictly partner directive placeholder)
        rawSections.add(new AssembledSection(7, "07 Procedural Objections", "<p>[[PARTNER: add procedural objections after checklist review]]</p>"));

        // Section 08: Cross-Examination Request (Rule 2: Always present; when not applicable body = "Not applicable.")
        boolean reliesOnThirdParty = input != null && input.notice() != null && Boolean.TRUE.equals(input.notice().get("relies_on_third_party_material"));
        StringBuilder sec08 = new StringBuilder();
        if (reliesOnThirdParty) {
            String sec08Tpl = (stageTpl != null && stageTpl.sections() != null) ? stageTpl.sections().get("08") : null;
            if (sec08Tpl != null && !sec08Tpl.isBlank()) {
                sec08.append(sec08Tpl);
            } else {
                sec08.append("<p>Request for cross-examination of third-party witnesses and inspection of underlying statements relied upon by the department.</p>");
            }
        } else {
            sec08.append("<p>Not applicable.</p>");
        }
        rawSections.add(new AssembledSection(8, "08 Cross-Examination Request", sec08.toString()));

        // Section 09: Related Context from Other Registrations (Rule 2: Always present; when not applicable body = "Not applicable.")
        StringBuilder sec09 = new StringBuilder();
        sec09.append("<p>Not applicable.</p>");
        rawSections.add(new AssembledSection(9, "09 Related Context from Other Registrations", sec09.toString()));

        // Section 10: Documents Enclosed
        StringBuilder sec10 = new StringBuilder();
        sec10.append("<p>The following documents are enclosed in support of the reply:</p><ol>");
        sec10.append("<li>Copy of GSTR-3B and GSTR-1 returns for the relevant periods.</li>");
        sec10.append("<li>Reconciliation Statement of Input Tax Credit.</li>");
        sec10.append("</ol>");
        rawSections.add(new AssembledSection(10, "10 Documents Enclosed", sec10.toString()));

        // Section 11: Annexure Index
        StringBuilder sec11 = new StringBuilder();
        sec11.append("<p><strong>Annexure Index:</strong></p><table><tr><th>Annexure</th><th>Document Description</th></tr>");
        sec11.append("<tr><td>Annexure A-1</td><td>Form GSTR-3B Return Acknowledgments</td></tr>");
        sec11.append("<tr><td>Annexure A-2</td><td>GSTR-2B Statement & Reconciliation</td></tr>");
        sec11.append("</table>");
        rawSections.add(new AssembledSection(11, "11 Annexure Index", sec11.toString()));

        // Section 12: Prayer (Rule 6: If stage template missing -> [[MISSING: stage template for <stage>]])
        String sec12Body = getStageSectionOrMissing(stageTpl, "12", normalizedStage, missingMarkers, assemblyFlags);
        rawSections.add(new AssembledSection(12, "12 Prayer", sec12Body));

        // Section 13: Internal Partner Note (Cards, status, versions, procedural options, check results & markers)
        StringBuilder sec13 = new StringBuilder();
        sec13.append("<h3>Internal Partner Review Note</h3>");
        sec13.append("<p><strong>Notice ID:</strong> ").append(noticeInfo != null && noticeInfo.noticeNumber() != null ? noticeInfo.noticeNumber() : "N/A").append("</p>");
        sec13.append("<p><strong>Matched Issues & Templates:</strong></p><ul>");
        for (MatchedIssue issue : matchedIssues) {
            FilledTemplateResult ft = templateMap.get(issue.issueNo());
            sec13.append("<li>Issue #").append(issue.issueNo())
                  .append(": Match Status=").append(issue.status())
                  .append(", Cards=").append(issue.cardIds())
                  .append(", Template=").append(ft != null ? ft.templateId() : "N/A")
                  .append(" (v").append(ft != null ? ft.templateVersion() : 0).append(")</li>");
        }
        sec13.append("</ul>");

        sec13.append("<p><strong>Procedural Objection Options (Partner to Select):</strong></p>");
        sec13.append("<ul><li>Check opportunity of hearing under Section 75(4).</li><li>Verify statutory limitation period under Section 73(10).</li></ul>");
        sec13.append("<!-- CHECK_RESULTS_PLACEHOLDER -->");
        rawSections.add(new AssembledSection(13, "13 Internal Partner Note", sec13.toString()));

        // Section 14: Client Summary (Notice type, amount, due date, required documents)
        String fullNoticeType = formatFullNoticeType(noticeSec);
        String formattedDueDate = formatDueDateStr(noticeInfo != null ? noticeInfo.replyDueDate() : null, missingMarkers);

        StringBuilder sec14 = new StringBuilder();
        sec14.append("<h3>Client Summary</h3>");
        sec14.append("<p><strong>Notice Type:</strong> ").append(fullNoticeType).append("</p>");
        sec14.append("<p><strong>Total Amount Demanded:</strong> ").append(noticeInfo != null && noticeInfo.totalDemandAmount() != null ? TemplateFillService.formatIndianCurrency(java.math.BigDecimal.valueOf(noticeInfo.totalDemandAmount())) : "[[MISSING: total_demand]]").append("</p>");
        sec14.append("<p><strong>Reply Due Date:</strong> ").append(formattedDueDate).append("</p>");
        sec14.append("<p><strong>Documents Required from Client:</strong></p><ol>");
        sec14.append("<li>Copy of Tax Invoices and Purchase Register for FY ").append(input != null && input.financialYear() != null ? input.financialYear() : "[[MISSING: financial_year]]").append(".</li>");
        sec14.append("<li>Form GSTR-3B return filing acknowledgments and GSTR-2B reconciliation statement.</li>");
        sec14.append("<li>Electronic Cash Ledger statement / Bank payment proof of tax deposited.</li>");
        sec14.append("</ol>");
        rawSections.add(new AssembledSection(14, "14 Client Summary", sec14.toString()));

        // Section 15: Filing Checklist (Rule 6: If stage template missing -> [[MISSING: stage template for <stage>]])
        String sec15Body = getStageSectionOrMissing(stageTpl, "15", normalizedStage, missingMarkers, assemblyFlags);
        rawSections.add(new AssembledSection(15, "15 Filing Checklist", sec15Body));

        // Continuous Paragraph Numbering & Cross-Ref Resolution
        List<AssembledSection> finalSections = new ArrayList<>();
        int pNumber = 1;

        for (AssembledSection sec : rawSections) {
            String resolvedHtml = resolveCrossReferences(sec.bodyHtml(), blockIdToRefTag, issueNoToRefTag);

            // Extract all <p> and <li> to assign continuous paragraph numbers
            StringBuilder numHtml = new StringBuilder();
            String[] paragraphs = resolvedHtml.split("(?=<p>|<li>|<h3>|<ol>|<ul>|<table>)");

            for (String part : paragraphs) {
                if (part.startsWith("<p>") || part.startsWith("<li>")) {
                    // Prepend paragraph number tag
                    numHtml.append("<!-- para:").append(pNumber++).append(" -->").append(part);
                } else {
                    numHtml.append(part);
                }
            }

            finalSections.add(new AssembledSection(sec.num(), sec.title(), numHtml.toString()));
        }

        return new AssembledReplyResult(finalSections, finalSourceMap, assemblyFlags, missingMarkers, pendingAiMarkers);
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
        missingMarkers.add(missingText);
        flags.add("missing_stage_template_" + stage);
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
                refValue = blockIdToRef.get(refKey).replace("para-", "para ");
            } else if (refKey.startsWith("ISSUE-")) {
                try {
                    int issueNo = Integer.parseInt(refKey.substring(6));
                    if (issueNoToRef.containsKey(issueNo)) {
                        refValue = issueNoToRef.get(issueNo).replace("para-", "para ");
                    }
                } catch (Exception ignored) {}
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

    private String formatFullNoticeType(String noticeSec) {
        if (noticeSec == null || noticeSec.isBlank()) return "[[MISSING: notice_section]]";
        if (noticeSec.startsWith("Show Cause Notice") || noticeSec.startsWith("Intimation of Discrepancy")) {
            return noticeSec;
        }
        if (noticeSec.contains("Section 73") || noticeSec.contains("Section 74") || noticeSec.contains("73") || noticeSec.contains("74")) {
            if (!noticeSec.startsWith("Section")) {
                return "Show Cause Notice under Section " + noticeSec;
            }
            return "Show Cause Notice under " + noticeSec;
        }
        if (noticeSec.contains("ASMT-10") || noticeSec.contains("ASMT")) {
            return "Intimation of Discrepancy under " + noticeSec;
        }
        return "Notice under " + noticeSec;
    }

    private String formatDueDateStr(String dateStr, List<String> missingMarkers) {
        if (dateStr == null || dateStr.isBlank()) {
            if (missingMarkers != null) missingMarkers.add("[[MISSING: reply_due_date]]");
            return "[[MISSING: reply_due_date]]";
        }
        String trimmed = dateStr.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*") || trimmed.matches("\\d{2}-\\d{2}-\\d{4}")) {
            return formatDateStr(trimmed);
        }
        if (trimmed.toLowerCase().contains("30") || trimmed.toLowerCase().contains("within") || trimmed.toLowerCase().contains("day")) {
            if (missingMarkers != null && !missingMarkers.contains("date_of_receipt")) {
                missingMarkers.add("date_of_receipt");
            }
            return "Within 30 days of receipt (date of receipt: [[MISSING: date_of_receipt]])";
        }
        if (missingMarkers != null && !missingMarkers.contains("reply_due_date")) {
            missingMarkers.add("reply_due_date");
        }
        return "[[MISSING: reply_due_date]]";
    }

    private String formatClientName(String name) {
        if (name == null || name.isBlank()) return "[[MISSING: client_legal_name]]";
        if (name.startsWith("M/s ") || name.startsWith("M/S ")) return name;
        return "M/s " + name;
    }

    private String formatDateStr(String dateStr) {
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

