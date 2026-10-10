package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.CitationVerificationResult;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.HighStakesDraftingAgent.HighStakesResult;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.AiIssueSectionRecord;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.ReplyAssemblyService.HighStakesContent;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import com.noticedesk.api.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Architecture v7 drafting pipeline.
 * <ol>
 *   <li>Stage 1: issue matching against the cached card catalogue (status full | partial | none).</li>
 *   <li>Stage 2: code-only template fill for full matches.</li>
 *   <li>Stage 3: AI writers for partial (PartialDraftingAgent) and unmatched (NewIssueDraftingAgent) issues,
 *       high-stakes mode (HighStakesDraftingAgent writes 01/03 and the AI parts of 04/05/06), then
 *       IndianKanoon verification of every AI citation.</li>
 *   <li>15-section assembly, deterministic code checks (draft_flags) and the Section 13 partner note.</li>
 * </ol>
 */
@Service
@Slf4j
public class V7DraftingPipelineService {

    private final IssueMatchingAgent issueMatchingAgent;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateFillService templateFillService;
    private final ReplyAssemblyService replyAssemblyService;
    private final DraftCheckService draftCheckService;
    private final PartialDraftingAgent partialDraftingAgent;
    private final NewIssueDraftingAgent newIssueDraftingAgent;
    private final HighStakesDraftingAgent highStakesDraftingAgent;
    private final CitationVerificationAgent citationVerificationAgent;

    @Autowired
    public V7DraftingPipelineService(
            IssueMatchingAgent issueMatchingAgent,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TemplateFillService templateFillService,
            ReplyAssemblyService replyAssemblyService,
            DraftCheckService draftCheckService,
            PartialDraftingAgent partialDraftingAgent,
            NewIssueDraftingAgent newIssueDraftingAgent,
            HighStakesDraftingAgent highStakesDraftingAgent,
            CitationVerificationAgent citationVerificationAgent) {
        this.issueMatchingAgent = issueMatchingAgent;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.templateFillService = templateFillService;
        this.replyAssemblyService = replyAssemblyService;
        this.draftCheckService = draftCheckService;
        this.partialDraftingAgent = partialDraftingAgent;
        this.newIssueDraftingAgent = newIssueDraftingAgent;
        this.highStakesDraftingAgent = highStakesDraftingAgent;
        this.citationVerificationAgent = citationVerificationAgent;
    }

    /** Stage 1 + 2 only (no AI writers): partial / unmatched issues keep their [[PENDING_AI]] markers. */
    public V7DraftingPipelineService(
            IssueMatchingAgent issueMatchingAgent,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TemplateFillService templateFillService,
            ReplyAssemblyService replyAssemblyService,
            DraftCheckService draftCheckService) {
        this(issueMatchingAgent, jdbc, objectMapper, templateFillService, replyAssemblyService, draftCheckService, null, null, null, null);
    }

    /** Stage 3 output of one issue before citation clean-up. */
    private record IssueAiOutput(MatchedIssue issue, String writer, List<AiFragment> fragments, List<RawAiCitation> citations,
                                 List<String> documents, String summaryLine, String strengthNote,
                                 String model, String promptVersion) {}

    public DraftingPipelineResult runPipeline(DraftingInput input) {
        return runPipeline(input, null, null);
    }

    public DraftingPipelineResult runPipeline(DraftingInput input, UUID tenantIdArg, UUID noticeIdArg) {
        log.info("V7DraftingPipelineService starting Stage 1 issue matching execution");

        UUID tenantId = tenantIdArg != null ? tenantIdArg : UUID.randomUUID();
        UUID noticeId = noticeIdArg;
        if (noticeId == null && input != null && input.notice() != null && input.notice().get("notice_id") != null) {
            try {
                noticeId = UUID.fromString(input.notice().get("notice_id").toString());
            } catch (IllegalArgumentException ignored) {
                // bench / test notices use non-UUID ids
            }
        }
        if (noticeId == null) noticeId = UUID.randomUUID();

        String noticeType = "scn_73";
        if (input != null && input.notice() != null && input.notice().get("document_type") != null) {
            noticeType = input.notice().get("document_type").toString();
        }

        String fullOcrText = input != null && input.noticeOcrExcerpt() != null ? input.noticeOcrExcerpt() : "";
        MatchingResult matchingResult = issueMatchingAgent.matchNoticeWithOcrText(fullOcrText, noticeType, tenantId, noticeId);
        List<MatchedIssue> issues = matchingResult != null && matchingResult.issues() != null ? matchingResult.issues() : List.of();

        persistIssueMatches(tenantId, noticeId, issues);
        log.info("Stage 1 Issue Matching complete: matched {} issues", issues.size());

        // ---- Stage 2: code template fill
        List<FilledTemplateResult> filledTemplates = new ArrayList<>();
        for (MatchedIssue issue : issues) {
            filledTemplates.add(templateFillService.fillTemplateForIssue(issue, matchingResult.notice(), input));
        }

        // ---- Stage 3: AI writers
        Object rawSection = input != null && input.notice() != null ? input.notice().get("section") : null;
        String noticeSectionRaw = rawSection != null && !rawSection.toString().isBlank() ? rawSection.toString().trim() : null;
        Double totalDemand = matchingResult != null && matchingResult.notice() != null ? matchingResult.notice().totalDemandAmount() : null;
        boolean highStakes = highStakesDraftingAgent != null
                && highStakesDraftingAgent.isHighStakesActive(noticeSectionRaw, noticeType, totalDemand);

        List<String> notes = new ArrayList<>();
        if (highStakes) {
            notes.add("High-stakes mode active (Section 74/74A, appeal stage or demand threshold): sections 01/03 narrative and AI parts of 04/05/06 written by the high-stakes model.");
        }

        List<IssueAiOutput> aiOutputs = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            MatchedIssue issue = issues.get(i);
            FilledTemplateResult ft = filledTemplates.get(i);
            if (!ReplyAssemblyService.needsAiWriter(issue, ft)) continue;
            IssueAiOutput out = runIssueWriter(issue, ft, matchingResult, fullOcrText, noticeType, noticeId, highStakes, notes);
            if (out != null) aiOutputs.add(out);
        }

        HighStakesResult hsResult = null;
        if (highStakes) {
            hsResult = highStakesDraftingAgent.generateHighStakesDraft(matchingResult, input, noticeType, noticeId);
            if (hsResult == null) {
                notes.add("High-stakes writer returned no usable output; sections 01/03 contain the code-built facts only.");
            }
        }

        // ---- Stage 3: citation check on every AI citation
        List<RawAiCitation> allCitations = new ArrayList<>();
        aiOutputs.forEach(o -> allCitations.addAll(o.citations()));
        if (hsResult != null && hsResult.citations() != null) allCitations.addAll(hsResult.citations());

        CitationVerificationResult citationResult = verifyCitations(allCitations, noticeId);
        List<VerifiedCitation> verified = citationResult.citations();

        Set<String> removedNames = new LinkedHashSet<>();
        for (VerifiedCitation vc : verified) {
            if (CitationVerificationAgent.NOT_FOUND.equals(vc.status())) removedNames.add(vc.caseName());
            notes.add("Citation " + HtmlSanitizer.sanitize(vc.caseName()) + " (" + Objects.toString(vc.court(), "court not given")
                    + ", " + Objects.toString(vc.year(), "year not given") + "): " + vc.status()
                    + (vc.details() != null ? " - " + HtmlSanitizer.sanitize(vc.details()) : "")
                    + (CitationVerificationAgent.NOT_FOUND.equals(vc.status()) ? " - citation removed from the text, argument kept" : ""));
        }
        if (citationResult.flags().contains("MISSING_INDIANKANOON_API_TOKEN")) {
            notes.add("INDIANKANOON_API_TOKEN is not set: AI citations were NOT checked.");
        }

        Map<Integer, AiIssueContent> aiContent = new LinkedHashMap<>();
        List<AiIssueSectionRecord> aiRecords = new ArrayList<>();
        for (IssueAiOutput o : aiOutputs) {
            List<AiFragment> cleaned = new ArrayList<>();
            for (AiFragment f : o.fragments()) {
                String html = f.html();
                for (String name : removedNames) html = CitationVerificationAgent.removeCitationText(html, name);
                html = HtmlSanitizer.sanitize(html);
                if (!html.isBlank()) cleaned.add(new AiFragment(f.section(), f.afterBlock(), html));
            }
            aiContent.put(o.issue().issueNo(), new AiIssueContent(o.issue().issueNo(), o.writer(), cleaned, o.documents(),
                    o.summaryLine() != null ? HtmlSanitizer.sanitize(stripCitations(o.summaryLine(), removedNames)) : null));
            aiRecords.add(new AiIssueSectionRecord(o.issue().issueNo(),
                    o.model() != null ? o.model() : "unknown",
                    o.promptVersion() != null ? o.promptVersion() : "unknown",
                    aiOutputJson(o, cleaned, verified), "pending_review"));
            if (o.strengthNote() != null && !o.strengthNote().isBlank()) {
                notes.add("Issue #" + o.issue().issueNo() + " strength note: " + HtmlSanitizer.sanitize(o.strengthNote()));
            }
        }

        HighStakesContent hsContent = null;
        if (hsResult != null) {
            hsContent = new HighStakesContent(
                    HtmlSanitizer.sanitize(stripCitations(hsResult.section01Html(), removedNames)),
                    HtmlSanitizer.sanitize(stripCitations(hsResult.section03Html(), removedNames)));
        }

        // ---- Assembly + code checks
        AssembledReplyResult assemblyResult = replyAssemblyService.assembleReply(
                matchingResult, filledTemplates, input, noticeType, aiContent, hsContent, notes);

        List<DraftCheckResult> checkResults = new ArrayList<>(
                draftCheckService.runAllChecks(matchingResult, assemblyResult, noticeSectionRaw, noticeType));
        if (!allCitations.isEmpty()) {
            checkResults.addAll(draftCheckService.citationChecks(verified));
        }

        List<DraftSection> draftSections = new ArrayList<>();
        String checkHtml = renderCheckResults(checkResults, assemblyResult,
                assemblyResult.sections().stream().map(AssembledSection::bodyHtml).toList());
        for (AssembledSection s : assemblyResult.sections()) {
            String bodyHtml = s.bodyHtml();
            if (s.num() == 13) {
                bodyHtml = bodyHtml.replace("<!-- CHECK_RESULTS_PLACEHOLDER -->", checkHtml);
            }
            draftSections.add(new DraftSection(s.num(), s.title(), bodyHtml));
        }

        Set<String> models = new LinkedHashSet<>();
        aiRecords.forEach(r -> models.add(r.model()));
        if (hsResult != null && hsResult.model() != null) models.add(hsResult.model());

        GeneratedDraft generatedDraft = new GeneratedDraft(
                draftSections,
                "v7 assembled draft; see Section 13",
                "Firm Standard Disclaimers Apply",
                aiOutputs.isEmpty() && hsResult == null ? "v7-assembled" : "v7-assembled+stage3",
                models.isEmpty() ? "none" : String.join(",", models),
                "formal",
                matchingResult != null ? matchingResult.outputTokens() : 0,
                draftSections.size()
        );

        log.info("v7 assembly complete: {} sections, {} AI issues, {} AI citations, {} code checks",
                draftSections.size(), aiOutputs.size(), verified.size(), checkResults.size());

        return new DraftingPipelineResult(
                generatedDraft,
                verified,
                citationResult.summary(),
                0, 0, aiOutputs.size() + (hsResult != null ? 1 : 0),
                matchingResult != null ? matchingResult.inputTokens() : 0,
                matchingResult != null ? matchingResult.outputTokens() : 0,
                "end_turn",
                assemblyResult.paragraphSourceMap(),
                checkResults,
                aiRecords
        );
    }

    private IssueAiOutput runIssueWriter(MatchedIssue issue, FilledTemplateResult ft, MatchingResult mr, String fullOcrText,
                                         String stage, UUID noticeId, boolean highStakes, List<String> notes) {
        boolean partial = "partial".equalsIgnoreCase(issue.status());
        if (partial && partialDraftingAgent != null) {
            ReplyTemplate template = null;
            if (ft != null && ft.cardId() != null && !"NONE".equals(ft.templateId()) && !"MISSING_PERIOD".equals(ft.templateId())) {
                Object period = issue.facts() != null ? issue.facts().get("period") : null;
                template = templateFillService.selectTemplateForCardAndPeriod(ft.cardId(), period != null ? period.toString() : null);
            }
            PartialDraftingResult r = partialDraftingAgent.generatePartialDrafting(issue, mr.notice(), template, stage, noticeId, highStakes);
            if (r == null || r.sections() == null || r.sections().isEmpty()) {
                notes.add("Issue #" + issue.issueNo() + ": partial-drafting writer returned no usable output; [[PENDING_AI]] kept.");
                return null;
            }
            List<AiFragment> frags = new ArrayList<>();
            for (PartialSectionAddition a : r.sections()) {
                if (a.section() == 4 || a.section() == 5 || a.section() == 6) {
                    frags.add(new AiFragment(a.section(), a.afterBlock(), HtmlSanitizer.sanitize(a.html())));
                } else {
                    notes.add("Issue #" + issue.issueNo() + ": AI text for section " + a.section() + " ignored (writers may only add to 04/05/06).");
                }
            }
            return new IssueAiOutput(issue, "partial", frags, nn(r.citations()), nn(r.documents()), r.summaryLine(), null,
                    r.model(), r.promptVersion());
        }

        if (newIssueDraftingAgent == null) {
            return null;
        }
        if (!"none".equalsIgnoreCase(issue.status())) {
            notes.add("Issue #" + issue.issueNo() + " (" + issue.status() + "): no usable template, written by the new-issue writer.");
        }
        NewIssueDraftingResult r = newIssueDraftingAgent.generateNewIssueDraft(issue, mr.notice(), fullOcrText, stage, noticeId, highStakes);
        if (r == null) {
            notes.add("Issue #" + issue.issueNo() + ": new-issue writer returned no usable output; [[PENDING_AI]] kept.");
            return null;
        }
        List<AiFragment> frags = new ArrayList<>();
        if (r.section04Html() != null && !r.section04Html().isBlank()) frags.add(new AiFragment(4, null, HtmlSanitizer.sanitize(r.section04Html())));
        if (r.section05Html() != null && !r.section05Html().isBlank()) frags.add(new AiFragment(5, null, HtmlSanitizer.sanitize(r.section05Html())));
        if (r.section06Html() != null && !r.section06Html().isBlank()) frags.add(new AiFragment(6, null, HtmlSanitizer.sanitize(r.section06Html())));
        return new IssueAiOutput(issue, "new_issue", frags, nn(r.citations()), nn(r.documents()), r.summaryLine(),
                r.strengthNote(), r.model(), r.promptVersion());
    }

    private CitationVerificationResult verifyCitations(List<RawAiCitation> citations, UUID noticeId) {
        if (citations.isEmpty()) {
            return new CitationVerificationResult(Map.of("total", 0), List.of(), null, List.of());
        }
        if (citationVerificationAgent == null) {
            List<VerifiedCitation> notChecked = new ArrayList<>();
            for (RawAiCitation r : citations) {
                notChecked.add(new VerifiedCitation(r.caseName(), r.caseName(), r.citedFor(), CitationVerificationAgent.NOT_CHECKED,
                        null, null, 0.0, "flagged", "Citation checker not configured", r.court(), r.year(), r.quotedText()));
            }
            return new CitationVerificationResult(Map.of("total", notChecked.size(), "not_checked", notChecked.size()),
                    notChecked, null, List.of());
        }
        return citationVerificationAgent.verifyAiCitations(citations, noticeId);
    }

    private static String stripCitations(String html, Set<String> removedNames) {
        if (html == null) return null;
        String out = html;
        for (String name : removedNames) out = CitationVerificationAgent.removeCitationText(out, name);
        return out;
    }

    private static Map<String, Object> aiOutputJson(IssueAiOutput o, List<AiFragment> cleaned, List<VerifiedCitation> verified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("writer", o.writer());
        List<Map<String, Object>> secs = new ArrayList<>();
        for (AiFragment f : cleaned) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("section", f.section());
            s.put("after_block", f.afterBlock());
            s.put("html", f.html());
            secs.add(s);
        }
        m.put("sections", secs);
        List<Map<String, Object>> cits = new ArrayList<>();
        for (RawAiCitation c : o.citations()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("case", c.caseName());
            cm.put("court", c.court());
            cm.put("year", c.year());
            cm.put("quoted_text", c.quotedText());
            cm.put("cited_for", c.citedFor());
            verified.stream().filter(v -> Objects.equals(v.caseName(), c.caseName())).findFirst()
                    .ifPresent(v -> cm.put("check_status", v.status()));
            cits.add(cm);
        }
        m.put("citations", cits);
        m.put("documents", o.documents());
        m.put("summary_line", o.summaryLine());
        m.put("strength_note", o.strengthNote());
        return m;
    }

    private static final Pattern MISSING_MARKER = Pattern.compile("\\[\\[MISSING: ([^\\]]+)\\]\\]");

    private static String renderCheckResults(List<DraftCheckResult> checkResults, AssembledReplyResult assemblyResult,
                                             List<String> sectionsHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>Code Quality Check Results & Flags:</strong></p><table>");
        sb.append("<tr><th>Check Name</th><th>Status</th><th>Severity</th><th>Details</th></tr>");
        for (DraftCheckResult cr : checkResults) {
            sb.append("<tr><td>").append(cr.checkName())
              .append("</td><td>").append(cr.passed() ? "PASS" : "FAIL")
              .append("</td><td>").append(cr.severity())
              .append("</td><td>").append(cr.details() != null ? HtmlSanitizer.sanitize(new TreeMap<>(cr.details()).toString()) : "")
              .append("</td></tr>");
        }
        sb.append("</table>");
        // every [[MISSING: x]] in the draft (code-built and AI-written), plus code markers such as stage templates
        Set<String> missing = new LinkedHashSet<>();
        Matcher mm = MISSING_MARKER.matcher(String.join("\n", sectionsHtml));
        while (mm.find()) missing.add(mm.group(1).trim());
        for (String m : assemblyResult.missingMarkers()) {
            missing.add(m.replaceAll("^\\[\\[MISSING: |\\]\\]$", ""));
        }
        appendMarkerList(sb, "Missing Data Markers", new ArrayList<>(missing));
        appendMarkerList(sb, "Pending AI Markers", assemblyResult.pendingAiMarkers());
        return sb.toString();
    }

    private static void appendMarkerList(StringBuilder sb, String label, List<String> markers) {
        if (markers == null || markers.isEmpty()) {
            sb.append("<p><strong>").append(label).append(":</strong> None</p>");
            return;
        }
        sb.append("<p><strong>").append(label).append(":</strong></p><ul>");
        for (String m : markers) sb.append("<li>").append(m).append(" (severity: block)</li>");
        sb.append("</ul>");
    }

    private void persistIssueMatches(UUID tenantId, UUID noticeId, List<MatchedIssue> issues) {
        if (jdbc == null) return;
        for (MatchedIssue issue : issues) {
            try {
                String sql = """
                        INSERT INTO issue_matches (
                            tenant_id, notice_id, issue_no, status, card_ids, facts, why, flags
                        ) VALUES (
                            CAST(:tid AS UUID), CAST(:nid AS UUID), :issueNo, :status,
                            CAST(:cardIds AS JSONB), CAST(:facts AS JSONB), :why, CAST(:flags AS JSONB)
                        )
                        """;
                Map<String, Object> params = Map.of(
                        "tid", tenantId.toString(),
                        "nid", noticeId.toString(),
                        "issueNo", issue.issueNo(),
                        "status", issue.status(),
                        "cardIds", objectMapper.writeValueAsString(issue.cardIds()),
                        "facts", objectMapper.writeValueAsString(issue.facts()),
                        "why", issue.why() != null ? issue.why() : "",
                        "flags", objectMapper.writeValueAsString(issue.flags())
                );
                jdbc.update(sql, params);
            } catch (Exception e) {
                log.error("Failed to persist issue_match row for issue #{}", issue.issueNo(), e);
            }
        }
    }

    /**
     * Saves the Stage 3 AI output rows. Called by DraftingWorkflow after the draft row insert, inside the
     * same transaction (same pattern as draft_flags). Errors propagate so the transaction rolls back.
     */
    public void persistAiIssueSections(UUID tenantId, UUID noticeId, UUID draftId, List<AiIssueSectionRecord> records) {
        if (jdbc == null || records == null || records.isEmpty()) return;
        String sql = """
                INSERT INTO ai_issue_sections (tenant_id, notice_id, draft_id, issue_no, model, prompt_version, output, status)
                VALUES (:tenantId, :noticeId, :draftId, :issueNo, :model, :pv, CAST(:output AS JSONB), :status)
                """;
        for (AiIssueSectionRecord r : records) {
            try {
                jdbc.update(sql, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("noticeId", noticeId)
                        .addValue("draftId", draftId)
                        .addValue("issueNo", r.issueNo())
                        .addValue("model", r.model())
                        .addValue("pv", r.promptVersion())
                        .addValue("output", objectMapper.writeValueAsString(r.outputJson()))
                        .addValue("status", r.status()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialise ai_issue_sections output for issue #" + r.issueNo(), e);
            }
        }
    }

    private static <T> List<T> nn(List<T> l) {
        return l != null ? l : List.of();
    }
}
