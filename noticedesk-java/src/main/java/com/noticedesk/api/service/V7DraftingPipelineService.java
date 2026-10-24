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
import com.noticedesk.api.agent.TemplateVariableAgent;
import com.noticedesk.api.agent.TemplateVariableAgent.IssueVariableRequest;
import com.noticedesk.api.agent.TemplateVariableAgent.TemplateVariablesResult;
import com.noticedesk.api.agent.TemplateVariableAgent.VariableSpec;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.CodeDefectMerger;
import com.noticedesk.api.service.matching.DefectChecker;
import com.noticedesk.api.service.matching.DefectChecker.CodeDefect;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.DraftTextCleanup;
import com.noticedesk.api.service.matching.IssueFlags;
import com.noticedesk.api.service.matching.PrayerStripper;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.ReplyAssemblyService.HighStakesContent;
import com.noticedesk.api.service.matching.CardShortlister;
import com.noticedesk.api.service.matching.StageDetector;
import com.noticedesk.api.service.matching.StageResolver;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import com.noticedesk.api.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Architecture v7 drafting pipeline.
 * <ol>
 *   <li>Stage 1: issue matching against the cached card catalogue (status full | partial | none).</li>
 *   <li>Stage 1b: one TemplateVariableAgent call per notice for the card-specific template variables the
 *       matcher does not extract (merged into the issue facts before the fill).</li>
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
    private final TemplateVariableAgent templateVariableAgent;
    private final AppProperties properties;
    private final ApiUsageLogService apiUsageLogService;

    /** Draft flag (info): the template variable step made no call (no issue had a template with an unknown variable). */
    public static final String FLAG_VARIABLE_AGENT_SKIPPED = "info:variable_agent_skipped";
    /** Draft flag (warn): the template variable step was refused by the per-notice budget; placeholders stay [[MISSING]]. */
    public static final String FLAG_VARIABLE_AGENT_NOT_CALLED = "warn:variable_agent_not_called";
    /** Draft flag (warn): the template variable step failed or returned unusable output twice; placeholders stay [[MISSING]]. */
    public static final String FLAG_VARIABLE_AGENT_FAILED = "warn:variable_agent_failed";

    /** Client-facing placeholder for an AI issue the writer could not (or was not allowed to) write. */
    public static final String AI_FAILED_PLACEHOLDER = "[[PARTNER: AI draft failed — write manually]]";
    public static final String AI_SKIPPED_COST_CAP_PLACEHOLDER = "[[PARTNER: AI draft skipped — cost cap]]";
    /** Section 04 line for an unmatched issue with no substantive ground (new-issue writer not called). */
    public static final String NO_SUBSTANTIVE_GROUND_PLACEHOLDER =
            "[[PARTNER: no catalogue card and no substantive demand identified for this ground; review the document]]";
    /** Draft flag (info) raised when the new-issue writer was skipped for an issue without a substantive ground. */
    public static final String FLAG_NEW_ISSUE_WRITER_SKIPPED = "info:new_issue_writer_skipped";
    /**
     * Words in an unmatched issue's label / why that mark an administrative document rather than a ground
     * (a corrigendum, an intimation of hearing date, an acknowledgement, a reminder, no ground / demand).
     */
    static final List<String> ADMINISTRATIVE_ISSUE_WORDS =
            List.of("corrigendum", "hearing date", "acknowledg", "reminder", "no ground", "no demand");

    /** Draft flag (info): a primary card of the wrong response stage was swapped for its single sibling of the right stage. */
    public static final String FLAG_STAGE_CORRECTED = "info:stage_corrected";
    /** Draft flag (warn): a primary card's response stage differs from the input document's and no single sibling exists. */
    public static final String FLAG_STAGE_MISMATCH = "warn:stage_mismatch";

    /** Draft flag (info): the matching prompt carried a shortlist of the catalogue; details size, level, counts per source. */
    public static final String FLAG_SHORTLIST = "info:shortlist";
    /** Draft flag (warn): the shortlist's AI title pass failed or was refused; the shortlist used labels only. */
    public static final String FLAG_SHORTLIST_AI_SKIPPED = "warn:shortlist_ai_skipped";
    /** Draft flag (warn): the title pass answer was not readable JSON; its card ids were found in the text. */
    public static final String FLAG_SHORTLIST_AI_PARSE_FALLBACK = "warn:shortlist_ai_parse_fallback";

    /** Card read access for the post-matching stage correction; null (tests) skips the correction. */
    private CatalogueService catalogueService;

    @Autowired(required = false)
    public void setCatalogueService(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

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
            CitationVerificationAgent citationVerificationAgent,
            TemplateVariableAgent templateVariableAgent,
            AppProperties properties,
            ApiUsageLogService apiUsageLogService) {
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
        this.templateVariableAgent = templateVariableAgent;
        this.properties = properties;
        this.apiUsageLogService = apiUsageLogService;
    }

    /** Without the template variable step (callers wired before it existed). */
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
            CitationVerificationAgent citationVerificationAgent,
            AppProperties properties,
            ApiUsageLogService apiUsageLogService) {
        this(issueMatchingAgent, jdbc, objectMapper, templateFillService, replyAssemblyService, draftCheckService,
                partialDraftingAgent, newIssueDraftingAgent, highStakesDraftingAgent, citationVerificationAgent, null,
                properties, apiUsageLogService);
    }

    /** No per-notice call/cost limits (tests). */
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
        this(issueMatchingAgent, jdbc, objectMapper, templateFillService, replyAssemblyService, draftCheckService,
                partialDraftingAgent, newIssueDraftingAgent, highStakesDraftingAgent, citationVerificationAgent, null, null, null);
    }

    /** No per-notice call/cost limits, with the template variable step (tests). */
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
            CitationVerificationAgent citationVerificationAgent,
            TemplateVariableAgent templateVariableAgent) {
        this(issueMatchingAgent, jdbc, objectMapper, templateFillService, replyAssemblyService, draftCheckService,
                partialDraftingAgent, newIssueDraftingAgent, highStakesDraftingAgent, citationVerificationAgent,
                templateVariableAgent, null, null);
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
                                 String model, String promptVersion, boolean placeholder) {
        IssueAiOutput(MatchedIssue issue, String writer, List<AiFragment> fragments, List<RawAiCitation> citations,
                      List<String> documents, String summaryLine, String strengthNote, String model, String promptVersion) {
            this(issue, writer, fragments, citations, documents, summaryLine, strengthNote, model, promptVersion, false);
        }

        /** The issue is left to the partner: a [[PARTNER]] placeholder in sections 04 and 06, no model output. */
        static IssueAiOutput partnerPlaceholder(MatchedIssue issue) {
            return partnerPlaceholder(issue, AI_FAILED_PLACEHOLDER, "none (AI draft failed)");
        }

        /** As {@link #partnerPlaceholder(MatchedIssue)} when the per-notice budget refused the writer call. */
        static IssueAiOutput costCapPlaceholder(MatchedIssue issue) {
            return partnerPlaceholder(issue, AI_SKIPPED_COST_CAP_PLACEHOLDER, "none (AI draft skipped, cost cap)");
        }

        private static IssueAiOutput partnerPlaceholder(MatchedIssue issue, String marker, String writer) {
            String p = "<p>" + marker + "</p>";
            return new IssueAiOutput(issue, writer, List.of(new AiFragment(4, null, p), new AiFragment(6, null, p)),
                    List.of(), List.of(), marker, null, null, null, true);
        }

        /** A code-found defect with no catalogue card: its [[PARTNER]] note in Section 04 (rendered in 07), no writer call. */
        static IssueAiOutput codeDefectPlaceholder(MatchedIssue issue, String marker) {
            return new IssueAiOutput(issue, "none (defect found by code check, no catalogue card)",
                    List.of(new AiFragment(4, null, "<p>" + marker + "</p>")), List.of(), List.of(), marker, null, null, null, true);
        }

        /** An unmatched issue without a substantive ground: one [[PARTNER]] line in Section 04, no writer call. */
        static IssueAiOutput noSubstantiveGround(MatchedIssue issue) {
            String p = "<p>" + NO_SUBSTANTIVE_GROUND_PLACEHOLDER + "</p>";
            return new IssueAiOutput(issue, "none (new-issue writer skipped: no substantive ground)",
                    List.of(new AiFragment(4, null, p)), List.of(), List.of(), NO_SUBSTANTIVE_GROUND_PLACEHOLDER,
                    null, null, null, true);
        }
    }

    /**
     * True when an unmatched issue (status none, no card) carries no substantive ground for the new-issue writer:
     * its facts have neither an amount nor a legal section, or its label / why names an administrative document
     * ({@link #ADMINISTRATIVE_ISSUE_WORDS}: a corrigendum, an intimation of hearing date, an acknowledgement,
     * a reminder, "no ground" / "no demand").
     */
    static boolean lacksSubstantiveGround(MatchedIssue issue) {
        if (!"none".equalsIgnoreCase(issue.status()) || issue.primaryCardId() != null) return false;
        Map<String, Object> facts = issue.facts() != null ? issue.facts() : Map.of();
        boolean noAmount = isBlankFact(facts.get("amount"));
        boolean noSection = isBlankFact(facts.get("legal_section"));
        if (noAmount && noSection) return true;
        StringBuilder text = new StringBuilder();
        if (issue.why() != null) text.append(issue.why()).append(' ');
        for (String key : List.of("label", "ground", "title", "allegation", "issue")) {
            Object v = facts.get(key);
            if (v != null) text.append(v).append(' ');
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        return ADMINISTRATIVE_ISSUE_WORDS.stream().anyMatch(lower::contains);
    }

    private static boolean isBlankFact(Object v) {
        if (v == null) return true;
        String s = v.toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s);
    }

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

        Object rawSection = input != null && input.notice() != null ? input.notice().get("section") : null;
        String noticeSectionRaw = rawSection != null && !rawSection.toString().isBlank() ? rawSection.toString().trim() : null;
        Object triageType = input != null && input.notice() != null ? input.notice().get("document_type") : null;
        // stage from the notice's own provision (Section 171 / 74 / 74A / 73) before the triage label
        String noticeType = StageDetector.detect(noticeSectionRaw, triageType != null ? triageType.toString() : null);
        if (triageType != null && !noticeType.equalsIgnoreCase(triageType.toString())) {
            log.info("Stage {} chosen from notice section '{}' (triage document_type was {})", noticeType, noticeSectionRaw, triageType);
        }

        NoticeCallBudget budget = NoticeCallBudget.fromProperties(properties, apiUsageLogService);
        Set<String> stageFlags = new LinkedHashSet<>();

        String fullOcrText = input != null && input.noticeOcrExcerpt() != null ? input.noticeOcrExcerpt() : "";
        // the matching agent checks each attempt against the budget and records what it spends
        MatchingResult matchingResult = issueMatchingAgent.matchNoticeWithOcrText(fullOcrText, noticeType, tenantId, noticeId, budget);
        String matchingFailure = matchingFailure(matchingResult);
        if (matchingFailure != null) {
            // no scaffold draft from a fallback issue: the notice fails here, before any writer call
            log.error("v7 pipeline stopped for notice {}: {}", noticeId, matchingFailure);
            throw new MatchingFailedException(matchingFailure,
                    matchingResult != null && matchingResult.flags() != null && matchingResult.flags().contains(IssueMatchingAgent.FLAG_COST_CAP));
        }
        List<String> notes = new ArrayList<>();
        Map<String, Map<String, Object>> stageFlagDetails = new LinkedHashMap<>();

        // the shortlist the matcher saw: flag and partner note (an unmatched ground goes to the writer path as it is)
        recordShortlist(matchingResult, notes, stageFlags, stageFlagDetails);

        List<MatchedIssue> issues = matchingResult != null && matchingResult.issues() != null ? matchingResult.issues() : List.of();
        log.info("Stage 1 Issue Matching complete: matched {} issues {} (catalogue_hash={})", issues.size(),
                issues.stream().map(i -> i.status() + i.cardIds()).toList(), matchingResult.catalogueHash());

        // ---- Stage 1a0: procedural defects tested by code (limitation, premature s.74, DIN, signature, DRC-01A reply
        // time), merged into the matched issues; the matcher's defect_checks stay as a second opinion
        MatchingResult withCodeDefects = applyCodeDefects(matchingResult, triageType != null ? triageType.toString() : null,
                noticeType, fullOcrText, noticeId, notes);
        if (withCodeDefects != matchingResult) {
            matchingResult = withCodeDefects;
            issues = matchingResult.issues();
        }

        // ---- Stage 1a: response-stage correction of the primary cards by code, before any template is selected
        List<MatchedIssue> stageCorrected = applyStageCorrection(issues, triageType != null ? triageType.toString() : null,
                fullOcrText, noticeId, notes, stageFlags, stageFlagDetails);
        if (stageCorrected != issues) {
            issues = stageCorrected;
            matchingResult = matchingResult.withIssues(issues);
        }

        // ---- Stage 1b: card-specific template variables the matcher does not extract (one call per notice)
        List<MatchedIssue> withVariables = extractTemplateVariables(issues, matchingResult, input, fullOcrText, noticeId, budget,
                notes, stageFlags);
        if (withVariables != issues) {
            issues = withVariables;
            matchingResult = matchingResult.withIssues(issues);
        }
        persistIssueMatches(tenantId, noticeId, issues);

        // ---- Stage 2: code template fill
        List<FilledTemplateResult> filledTemplates = new ArrayList<>();
        for (MatchedIssue issue : issues) {
            filledTemplates.add(templateFillService.fillTemplateForIssue(issue, matchingResult.notice(), input));
        }

        // ---- Stage 3: AI writers
        Double totalDemand = matchingResult != null && matchingResult.notice() != null ? matchingResult.notice().totalDemandAmount() : null;
        boolean highStakes = highStakesDraftingAgent != null
                && highStakesDraftingAgent.isHighStakesActive(noticeSectionRaw, noticeType, totalDemand);

        if (highStakes) {
            notes.add("High-stakes mode active (Section 74/74A, appeal stage or demand threshold): sections 01/03 narrative and AI parts of 04/05/06 written by the high-stakes model.");
        }

        List<IssueAiOutput> aiOutputs = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            MatchedIssue issue = issues.get(i);
            FilledTemplateResult ft = filledTemplates.get(i);
            // a full match filled from a template (any version chosen by period overlap) never reaches a writer
            if (!ReplyAssemblyService.needsAiWriter(issue, ft)) continue;
            Object codeDefectNote = issue.facts() != null ? issue.facts().get(CodeDefectMerger.FACT_PARTNER_NOTE) : null;
            if (codeDefectNote != null && issue.primaryCardId() == null) {
                // a defect found by code with no catalogue card for its regime: the partner drafts it, no writer call
                notes.add("Issue #" + issue.issueNo() + ": defect found by code check has no catalogue card for this regime;"
                        + " AI writer not called, partner to draft.");
                aiOutputs.add(IssueAiOutput.codeDefectPlaceholder(issue, codeDefectNote.toString()));
                continue;
            }
            if (newIssueDraftingAgent != null && lacksSubstantiveGround(issue)) {
                // a corrigendum / hearing intimation / acknowledgement / reminder, or no amount and no provision:
                // nothing for the new-issue writer to argue; the partner reviews the document
                log.info("Issue #{}: new-issue writer skipped (no catalogue card and no substantive ground)", issue.issueNo());
                notes.add("Issue #" + issue.issueNo() + ": new-issue writer not called (no catalogue card and no substantive demand"
                        + " or provision in the ground); the partner reviews the document.");
                stageFlags.add(FLAG_NEW_ISSUE_WRITER_SKIPPED);
                aiOutputs.add(IssueAiOutput.noSubstantiveGround(issue));
                continue;
            }
            IssueAiOutput out = runIssueWriter(issue, ft, matchingResult, fullOcrText, noticeType, noticeId, highStakes, notes,
                    budget, stageFlags);
            if (out != null) aiOutputs.add(out);
        }

        HighStakesResult hsResult = null;
        if (highStakes) {
            String refusal = budget.refusalReason(NoticeCallBudget.HIGH_STAKES);
            if (refusal != null) {
                notes.add("High-stakes sections 01/03 not written (" + refusal + "); they contain the code-built facts only.");
            } else {
                int refusalsBefore = budget.budgetRefusals();
                hsResult = highStakesDraftingAgent.generateHighStakesDraft(matchingResult, input, noticeType, noticeId, budget);
                if (hsResult == null && budget.budgetRefusals() > refusalsBefore) {
                    notes.add("High-stakes sections 01/03 not written (" + budget.lastRefusal() + "); they contain the code-built facts only.");
                } else if (hsResult == null) {
                    notes.add("High-stakes writer returned no usable output; sections 01/03 contain the code-built facts only.");
                }
            }
        }

        // ---- Stage 3: citation check on every AI citation (each case / provision once)
        List<RawAiCitation> rawCitations = new ArrayList<>();
        aiOutputs.forEach(o -> rawCitations.addAll(o.citations()));
        if (hsResult != null && hsResult.citations() != null) rawCitations.addAll(hsResult.citations());
        List<RawAiCitation> allCitations = CitationVerificationAgent.dedupeCitations(rawCitations);
        if (allCitations.size() < rawCitations.size()) {
            log.info("AI citations: {} listed, {} after removing duplicates", rawCitations.size(), allCitations.size());
        }

        // IndianKanoon calls are priced from configuration and count against the same per-notice cap
        CitationVerificationResult citationResult = verifyCitations(allCitations, noticeId, budget);
        List<VerifiedCitation> verified = citationResult.citations();

        Set<String> notFoundIds = new HashSet<>();
        for (VerifiedCitation vc : verified) {
            if (CitationVerificationAgent.NOT_FOUND.equals(vc.status())) notFoundIds.add(CitationVerificationAgent.citationIdentity(vc.caseName()));
        }
        Set<String> removedNames = new LinkedHashSet<>();   // every spelling the writers used for a NOT_FOUND case
        for (RawAiCitation rc : rawCitations) {
            if (notFoundIds.contains(CitationVerificationAgent.citationIdentity(rc.caseName()))) removedNames.add(rc.caseName());
        }
        for (VerifiedCitation vc : verified) {
            notes.add("Citation " + HtmlSanitizer.sanitize(vc.caseName()) + " (" + Objects.toString(vc.court(), "court not given")
                    + ", " + Objects.toString(vc.year(), "year not given") + "): " + vc.status()
                    + (vc.details() != null ? " - " + HtmlSanitizer.sanitize(vc.details()) : "")
                    + (CitationVerificationAgent.NOT_FOUND.equals(vc.status()) ? " - citation removed from the text, argument kept" : ""));
        }
        if (citationResult.flags().contains("MISSING_INDIANKANOON_API_TOKEN")) {
            notes.add("INDIANKANOON_API_TOKEN is not set: AI citations were NOT checked.");
        }
        if (citationResult.flags().contains(CitationVerificationAgent.FLAG_IK_COST_CAP)) {
            notes.add("Per-notice cost cap reached before every citation was checked: remaining citations are NOT_CHECKED.");
        }

        Map<Integer, AiIssueContent> aiContent = new LinkedHashMap<>();
        List<AiIssueSectionRecord> aiRecords = new ArrayList<>();
        for (IssueAiOutput o : aiOutputs) {
            List<AiFragment> cleaned = new ArrayList<>();
            int prayerParas = 0;
            for (AiFragment f : o.fragments()) {
                String html = f.html();
                for (String name : removedNames) html = CitationVerificationAgent.removeCitationText(html, name);
                if (f.section() == 4 || f.section() == 6) {
                    // Section 12 is the only prayer: drop one the writer appended
                    PrayerStripper.Stripped st = PrayerStripper.stripTrailingPrayer(html);
                    prayerParas += st.removed();
                    html = st.html();
                }
                html = HtmlSanitizer.sanitize(html);
                if (!html.isBlank()) cleaned.add(new AiFragment(f.section(), f.afterBlock(), html));
            }
            if (prayerParas > 0) {
                stageFlags.add("info:ai_prayer_stripped");
                notes.add("Issue #" + o.issue().issueNo() + ": " + prayerParas
                        + " prayer paragraph(s) written by the AI removed; Section 12 is the reply's only prayer.");
            }
            aiContent.put(o.issue().issueNo(), new AiIssueContent(o.issue().issueNo(), o.writer(), cleaned, o.documents(),
                    o.summaryLine() != null ? HtmlSanitizer.sanitize(stripCitations(o.summaryLine(), removedNames)) : null));
            if (o.placeholder()) continue;
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
        // the provision the draft names: the matcher's reading before the notice record's; when it differs, the stage
        // template follows it (a Section 50 letter or Section 75 order triaged scn_73 takes no Section 73 template)
        String draftSection = ReplyAssemblyService.resolveNoticeSection(matchingResult, noticeSectionRaw);
        String draftStage = noticeType;
        if (draftSection != null && !ReplyAssemblyService.sameProvision(draftSection, noticeSectionRaw)) {
            draftStage = StageDetector.detect(draftSection, triageType != null ? triageType.toString() : null);
            log.info("Draft section {} read by the matcher (notice record '{}'): stage template {} -> {}",
                    draftSection, noticeSectionRaw, noticeType, draftStage);
        }
        AssembledReplyResult assemblyResult = replyAssemblyService.assembleReply(
                matchingResult, filledTemplates, input, draftStage, aiContent, hsContent, notes);

        List<DraftCheckResult> checkResults = new ArrayList<>(
                draftCheckService.runAllChecks(matchingResult, assemblyResult, draftSection, draftStage));
        if (!allCitations.isEmpty()) {
            checkResults.addAll(draftCheckService.citationChecks(verified));
        }
        Set<String> flags = new LinkedHashSet<>(stageFlags);
        Map<String, Map<String, Object>> flagDetails = new LinkedHashMap<>(stageFlagDetails);
        for (FilledTemplateResult ft : filledTemplates) {
            if (ft == null) continue;
            if (ft.flags() != null) flags.addAll(ft.flags());
            // the card id and the two date windows behind a template selection flag (first issue wins)
            ft.flagDetails().forEach((f, d) -> flagDetails.putIfAbsent(f, d));
        }
        if (assemblyResult.flags() != null) flags.addAll(assemblyResult.flags());
        for (DraftCheckResult cr : draftCheckService.flagChecks(flags)) {
            Object flag = cr.details() != null ? cr.details().get("flag") : null;
            Map<String, Object> extra = flag != null ? flagDetails.get(flag.toString()) : null;
            if (extra == null || extra.isEmpty()) {
                checkResults.add(cr);
            } else {
                Map<String, Object> merged = new LinkedHashMap<>(cr.details());
                merged.putAll(extra);
                checkResults.add(new DraftCheckResult(cr.checkName(), cr.severity(), cr.passed(), merged));
            }
        }

        // issue-level flags (stage correction, code defect checks) as "<FLAG>#<issue no>" on the draft's flag list
        checkResults.addAll(IssueFlags.checkResults(issues));

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

    /**
     * Stage 1b. For every issue whose card has a template, the template's declared variables that the pipeline
     * cannot fill (not a client.* / notice.* value it already has, not one of the matcher's own facts) are asked of
     * the {@link TemplateVariableAgent} in ONE call for the notice; the values come back merged into the issue facts
     * under the key {@link TemplateFillService#factKeyForVariable} so the fill resolves the placeholders. No call is
     * made (flag {@link #FLAG_VARIABLE_AGENT_SKIPPED}) when no issue has a template or no variable is unknown; a
     * refused or failed call leaves every placeholder a [[MISSING: ...]] marker. Returns the same list instance when
     * nothing changed.
     */
    List<MatchedIssue> extractTemplateVariables(List<MatchedIssue> issues, MatchingResult mr, DraftingInput input, String fullOcrText,
                                                UUID noticeId, NoticeCallBudget budget, List<String> notes, Set<String> flags) {
        if (templateVariableAgent == null || issues == null || issues.isEmpty()) return issues;

        List<IssueVariableRequest> requests = new ArrayList<>();
        for (MatchedIssue issue : issues) {
            String cardId = issue.primaryCardId();
            if (cardId == null) continue;
            Object period = issue.facts() != null ? issue.facts().get("period") : null;
            // without a period the fill stops at [[MISSING: period]] before any template (rule 5): nothing to ask for
            if (period == null || period.toString().isBlank()) continue;
            ReplyTemplate template = templateFillService.selectTemplateForCardAndPeriod(cardId, period.toString());
            if (template == null) continue;
            Map<String, Object> context = templateFillService.buildContextMap(issue, mr != null ? mr.notice() : null, input);
            List<String> unknown = TemplateFillService.unknownVariables(template, context);
            if (unknown.isEmpty()) continue;
            List<VariableSpec> specs = new ArrayList<>();
            for (String name : unknown) specs.add(new VariableSpec(name, TemplateVariableAgent.hintFor(name)));
            requests.add(new IssueVariableRequest(issue.issueNo(), cardId, null, specs));
        }
        if (requests.isEmpty()) {
            log.info("Template variable step skipped for notice {}: no template with an unknown variable", noticeId);
            flags.add(FLAG_VARIABLE_AGENT_SKIPPED);
            return issues;
        }

        String refusal = budget != null ? budget.refusalReason(TemplateVariableAgent.AGENT_KEY) : null;
        if (refusal != null) {
            notes.add("Template variables not extracted (" + refusal + "); card-specific placeholders stay [[MISSING]].");
            flags.add(FLAG_VARIABLE_AGENT_NOT_CALLED);
            return issues;
        }
        int refusalsBefore = budget != null ? budget.budgetRefusals() : 0;
        TemplateVariablesResult result = templateVariableAgent.extractVariables(fullOcrText, requests, noticeId, budget);
        if (result == null) {
            if (budget != null && budget.budgetRefusals() > refusalsBefore) {
                notes.add("Template variables not extracted (" + budget.lastRefusal() + "); card-specific placeholders stay [[MISSING]].");
                flags.add(FLAG_VARIABLE_AGENT_NOT_CALLED);
            } else {
                notes.add("Template variable step returned no usable output; card-specific placeholders stay [[MISSING]].");
                flags.add(FLAG_VARIABLE_AGENT_FAILED);
            }
            return issues;
        }

        Map<Integer, IssueVariableRequest> requestByIssue = new HashMap<>();
        for (IssueVariableRequest r : requests) requestByIssue.put(r.issueNo(), r);
        List<MatchedIssue> merged = new ArrayList<>(issues.size());
        boolean changed = false;
        for (MatchedIssue issue : issues) {
            IssueVariableRequest request = requestByIssue.get(issue.issueNo());
            Map<String, String> values = result.valuesFor(issue.issueNo());
            if (request == null || values.isEmpty()) {
                merged.add(issue);
                continue;
            }
            // only the variables asked for this issue, in the order asked (a name the model added is ignored)
            Map<String, Object> facts = new LinkedHashMap<>(issue.facts() != null ? issue.facts() : Map.of());
            List<String> filled = new ArrayList<>();
            for (String name : request.variableNames()) {
                String value = values.get(name);
                if (value == null || value.isBlank()) continue;
                facts.put(TemplateFillService.factKeyForVariable(name), value);
                filled.add(name + "=" + HtmlSanitizer.sanitize(value));
            }
            if (filled.isEmpty()) {
                merged.add(issue);
                continue;
            }
            notes.add("Issue #" + issue.issueNo() + ": template variables read from the document: " + String.join(", ", filled) + ".");
            merged.add(issue.withFacts(Collections.unmodifiableMap(facts)));
            changed = true;
        }
        if (!result.notFound().isEmpty()) {
            notes.add("Template variables the document does not state (left as [[MISSING]]): "
                    + HtmlSanitizer.sanitize(String.join(", ", result.notFound())) + ".");
        }
        log.info("Template variable step for notice {}: {} issue(s) asked, {} value(s) returned, {} not found",
                noticeId, requests.size(), result.valueCount(), result.notFound().size());
        return changed ? merged : issues;
    }

    /**
     * The shortlist the matcher saw: flag {@link #FLAG_SHORTLIST} with its details and one partner note line; a title
     * pass that could not be used adds {@link #FLAG_SHORTLIST_AI_SKIPPED}, one whose ids were found in unreadable JSON
     * {@link #FLAG_SHORTLIST_AI_PARSE_FALLBACK}. No second matching call is made: an issue with status none goes to
     * the writer path.
     */
    void recordShortlist(MatchingResult mr, List<String> notes, Set<String> flags, Map<String, Map<String, Object>> flagDetails) {
        CardShortlister.Shortlist first = mr != null ? mr.shortlist() : null;
        if (first == null) return;
        flags.add(FLAG_SHORTLIST);
        flagDetails.put(FLAG_SHORTLIST, first.details());
        notes.add("Catalogue shortlist: " + first.size() + " of " + first.catalogueSize() + " cards shown to the matcher ("
                + first.describe().substring(first.describe().indexOf('(') + 1));
        if (first.aiSkipped()) {
            flags.add(FLAG_SHORTLIST_AI_SKIPPED);
            notes.add("The shortlist's AI title pass gave no usable answer; the shortlist was chosen by labels only.");
        }
        if (first.aiParseFallback()) {
            flags.add(FLAG_SHORTLIST_AI_PARSE_FALLBACK);
            notes.add("The shortlist's AI title pass answer was not clean JSON; the card ids found in it were used.");
        }
    }

    /** Minimum days between a DRC-01A and its reply date (drafting.defects.drc01a-min-days, DEFECT_DRC01A_MIN_DAYS). */
    private int drc01aMinDays() {
        return properties != null && properties.getDrafting() != null && properties.getDrafting().getDefects() != null
                ? properties.getDrafting().getDefects().getDrc01aMinDays() : DefectChecker.DEFAULT_DRC01A_MIN_DAYS;
    }

    /**
     * Stage 1a0. Runs the {@link DefectChecker} on the input document (the matcher's notice block first, the text by
     * regex where a field is null) and merges each result into the matched issues ({@link CodeDefectMerger}): a defect
     * the matcher also raised is confirmed, one it missed is added (on the response stage's card, or status none with a
     * [[PARTNER]] note when no card covers the regime), and a matcher defect the code finds ok is kept with a warning.
     * One partner note lists every check; one per added / confirmed / disputed issue. Returns the same instance when
     * nothing changed.
     */
    MatchingResult applyCodeDefects(MatchingResult mr, String documentType, String stage, String documentText, UUID noticeId,
                                    List<String> notes) {
        if (mr == null) return mr;
        MatchedNoticeInfo n = mr.notice();
        String text = documentText != null ? documentText : "";
        ResponseStage responseStage = StageResolver.resolve(documentType, text);
        List<CodeDefect> defects = new DefectChecker(drc01aMinDays()).check(new DefectChecker.Input(text,
                n != null ? n.issueDate() : null, n != null ? n.financialYear() : null, n != null ? n.noticeNumber() : null,
                n != null ? n.din() : null, documentType, stage, responseStage));
        notes.add("Code defect checks: " + String.join("; ", defects.stream()
                .map(d -> d.check() + "=" + d.result() + " (" + HtmlSanitizer.sanitize(d.basis()) + ")").toList()) + ".");
        log.info("Notice {} code defect checks: {}", noticeId, defects.stream().map(d -> d.check() + "=" + d.result()).toList());

        Map<String, IssueCard> cards = catalogueService != null ? catalogueService.cardsById() : Map.of();
        Predicate<String> cardExists = cards.isEmpty() ? id -> true : cards::containsKey;
        String period = n != null && n.financialYear() != null && !n.financialYear().isBlank() ? n.financialYear()
                : n != null && n.taxPeriod() != null && !n.taxPeriod().isBlank() ? n.taxPeriod() : null;
        if (period == null) {
            Integer fy = DefectChecker.financialYearStart(null, text);
            if (fy != null) period = "FY " + DefectChecker.fyLabel(fy);
        }
        CodeDefectMerger.Merged merged = CodeDefectMerger.merge(mr.issues(), mr.paraMap(), defects,
                responseStage == ResponseStage.APPEAL, cardExists, period);
        for (CodeDefectMerger.Outcome o : merged.outcomes()) {
            String basis = HtmlSanitizer.sanitize(o.basis());
            switch (o.action()) {
                case "added" -> notes.add("Issue #" + o.issueNo() + ": defect added by code check " + o.check() + " (" + basis + "); "
                        + (o.cardId() != null ? "card " + o.cardId() + "." : "no catalogue card for this regime, partner to draft."));
                case "confirmed" -> notes.add("Issue #" + o.issueNo() + ": defect confirmed by code check " + o.check() + " (" + basis + ").");
                default -> notes.add("Issue #" + o.issueNo() + ": code check " + o.check() + " finds no defect (" + basis
                        + "); the matcher's defect issue is kept for the partner to decide.");
            }
            log.info("Notice {} issue #{}: code check {} {}", noticeId, o.issueNo(), o.check(), o.action());
        }
        return merged.changed(mr.issues()) ? mr.withIssues(merged.issues(), merged.paraMap()) : mr;
    }

    /**
     * Stage 1a. The response stage the input document calls for ({@link StageResolver}) is tested against every
     * primary card; a card of the wrong stage class is swapped for its single sibling of the right class
     * ({@link #correctResponseStage}). Flags {@link #FLAG_STAGE_CORRECTED} / {@link #FLAG_STAGE_MISMATCH} with the
     * corrections as detail, one partner note and one log line per issue. Returns the same list instance when nothing
     * changed (no catalogue access, a stage the resolver cannot place, a revocation stage, or every card right).
     */
    List<MatchedIssue> applyStageCorrection(List<MatchedIssue> issues, String documentType, String documentText, UUID noticeId,
                                            List<String> notes, Set<String> flags, Map<String, Map<String, Object>> flagDetails) {
        if (catalogueService == null || issues == null || issues.isEmpty()) return issues;
        ResponseStage stage = StageResolver.resolve(documentType, documentText);
        if (stage == null || stage == ResponseStage.REVOCATION) return issues;
        Map<String, IssueCard> cards = catalogueService.cardsById();
        if (cards.isEmpty()) return issues;

        StageCorrection sc = correctResponseStage(issues, stage, cards);
        for (StageCorrection.Swap s : sc.swaps()) {
            log.info("Notice {} issue #{}: stage corrected {} -> {} (input calls for {})", noticeId, s.issueNo(), s.from(), s.to(), stage.label());
            notes.add("Issue #" + s.issueNo() + ": stage corrected from " + s.from() + " to " + s.to()
                    + " (the input document calls for " + stage.label() + "; " + s.from() + " kept as an alternate).");
        }
        for (StageCorrection.Mismatch m : sc.mismatches()) {
            log.info("Notice {} issue #{}: stage mismatch on {} ({} vs {}), {} candidate sibling(s), card kept",
                    noticeId, m.issueNo(), m.cardId(), m.inputStage(), m.cardStage(), m.candidates().size());
            notes.add("Issue #" + m.issueNo() + ": card " + m.cardId() + " is " + m.cardStage() + "-stage but the input document calls for "
                    + m.inputStage() + (m.candidates().isEmpty() ? "; no sibling card of that stage"
                    : "; more than one candidate sibling " + m.candidates()) + ", card kept: partner to check.");
        }
        if (!sc.swaps().isEmpty()) {
            flags.add(FLAG_STAGE_CORRECTED);
            flagDetails.put(FLAG_STAGE_CORRECTED, Map.of("corrections",
                    sc.swaps().stream().map(s -> s.from() + " -> " + s.to()).toList()));
        }
        if (!sc.mismatches().isEmpty()) {
            flags.add(FLAG_STAGE_MISMATCH);
            flagDetails.put(FLAG_STAGE_MISMATCH, Map.of("mismatches",
                    sc.mismatches().stream().map(m -> m.cardId() + ": " + m.inputStage() + " vs " + m.cardStage()).toList()));
        }
        return sc.issues();
    }

    /** Result of {@link #correctResponseStage}: the issues (same instance when unchanged), the swaps and the mismatches left. */
    record StageCorrection(List<MatchedIssue> issues, List<Swap> swaps, List<Mismatch> mismatches) {
        record Swap(int issueNo, String from, String to) {}

        record Mismatch(int issueNo, String cardId, String inputStage, String cardStage, List<String> candidates) {}
    }

    /** Stage class of a card for the correction: "appeal" or "reply" (everything that is not appeal-stage). */
    static String stageClass(IssueCard card) {
        return isAppealStageCard(card) ? "appeal" : "reply";
    }

    /** True for an appeal-stage card: its STAGES contain order_appeal, or its title starts with "Appeal" / "APL-01". */
    static boolean isAppealStageCard(IssueCard card) {
        if (card == null) return false;
        if (card.stages() != null) {
            for (String s : card.stages()) {
                if (s != null && "order_appeal".equalsIgnoreCase(s.trim())) return true;
            }
        }
        String title = card.title() != null ? card.title().trim().toLowerCase(Locale.ROOT) : "";
        return title.startsWith("appeal") || title.matches("^apl[\\s_-]*0?1\\b.*");
    }

    /**
     * For each issue whose primary card's stage class (appeal / reply) differs from the one {@code stage} calls for
     * (appeal for {@link ResponseStage#APPEAL}; reply for reply, objection and representation), the candidate
     * siblings are the catalogue cards named in the primary card's NOT THIS IF rules and the matcher's alternates
     * that are of the right class. Exactly one candidate: it becomes the primary, the old primary the first
     * alternate, the status is kept and the issue gets the flag {@code "stage_corrected: <old> -> <new>"}. Several:
     * the candidates already the primary card of another issue of the notice are dropped, and exactly one left is
     * swapped in the same way; when several are still left, the one candidate whose own NOT THIS IF rules name the
     * current primary (a mutual sibling) is swapped in, if exactly one is. None, or still several: the card is kept and the issue gets {@code "stage_mismatch: <input stage> vs <card stage>"}.
     */
    static StageCorrection correctResponseStage(List<MatchedIssue> issues, ResponseStage stage, Map<String, IssueCard> cards) {
        List<StageCorrection.Swap> swaps = new ArrayList<>();
        List<StageCorrection.Mismatch> mismatches = new ArrayList<>();
        if (issues == null || stage == null || stage == ResponseStage.REVOCATION || cards == null || cards.isEmpty()) {
            return new StageCorrection(issues, swaps, mismatches);
        }
        boolean wantAppeal = stage == ResponseStage.APPEAL;
        String wantedClass = wantAppeal ? "appeal" : "reply";
        List<MatchedIssue> out = new ArrayList<>(issues.size());
        boolean changed = false;
        for (int idx = 0; idx < issues.size(); idx++) {
            MatchedIssue issue = issues.get(idx);
            String primary = issue.primaryCardId();
            IssueCard card = primary != null ? cards.get(primary) : null;
            if (card == null || isAppealStageCard(card) == wantAppeal) {
                out.add(issue);
                continue;
            }
            Set<String> candidates = new LinkedHashSet<>();
            for (String rule : card.notThisIf() != null ? card.notThisIf() : List.<String>of()) {
                if (rule == null) continue;
                for (String id : cards.keySet()) {
                    if (!id.equals(primary) && namesCard(rule, id)) candidates.add(id);
                }
            }
            for (String alt : issue.alternateCardIds()) {
                if (alt != null && !alt.equals(primary) && cards.containsKey(alt)) candidates.add(alt);
            }
            candidates.removeIf(id -> isAppealStageCard(cards.get(id)) != wantAppeal);
            if (candidates.size() > 1) {
                // tie-break: a sibling that is already the primary card of another issue of this notice answers that issue
                Set<String> primariesElsewhere = new HashSet<>();
                for (int j = 0; j < issues.size(); j++) {
                    if (j == idx) continue;
                    String p = (j < out.size() ? out.get(j) : issues.get(j)).primaryCardId();
                    if (p != null) primariesElsewhere.add(p);
                }
                List<String> free = candidates.stream().filter(id -> !primariesElsewhere.contains(id)).toList();
                if (free.size() == 1) candidates = new LinkedHashSet<>(free);
            }
            if (candidates.size() > 1) {
                // tie-break: a mutual sibling, whose own NOT THIS IF rules name the current primary card
                List<String> mutual = candidates.stream().filter(id -> namesInNotThisIf(cards.get(id), primary)).toList();
                if (mutual.size() == 1) candidates = new LinkedHashSet<>(mutual);
            }

            List<String> issueFlags = new ArrayList<>(issue.flags() != null ? issue.flags() : List.of());
            if (candidates.size() == 1) {
                String sibling = candidates.iterator().next();
                List<String> alternates = new ArrayList<>();
                alternates.add(primary);
                for (String alt : issue.alternateCardIds()) {
                    if (alternates.size() >= 2) break;   // the matcher keeps at most two alternates
                    if (alt != null && !alt.equals(sibling) && !alternates.contains(alt)) alternates.add(alt);
                }
                issueFlags.add(StageResolver.ISSUE_FLAG_STAGE_CORRECTED + ": " + primary + " -> " + sibling);
                out.add(new MatchedIssue(issue.issueNo(), issue.status(), issue.kind(), List.of(sibling), List.copyOf(alternates),
                        issue.why(), issue.notThisIfChecked(), issue.facts(), issue.noticeParas(), List.copyOf(issueFlags),
                        issue.clientFactsToConfirm(), issue.parentIssueNo()));
                swaps.add(new StageCorrection.Swap(issue.issueNo(), primary, sibling));
            } else {
                String cardClass = stageClass(card);
                issueFlags.add(StageResolver.ISSUE_FLAG_STAGE_MISMATCH + ": " + stage.label() + " vs " + cardClass);
                out.add(new MatchedIssue(issue.issueNo(), issue.status(), issue.kind(), issue.cardIds(), issue.alternateCardIds(),
                        issue.why(), issue.notThisIfChecked(), issue.facts(), issue.noticeParas(), List.copyOf(issueFlags),
                        issue.clientFactsToConfirm(), issue.parentIssueNo()));
                mismatches.add(new StageCorrection.Mismatch(issue.issueNo(), primary, stage.label(), cardClass, List.copyOf(candidates)));
            }
            changed = true;
        }
        return new StageCorrection(changed ? out : issues, swaps, mismatches);
    }

    /** True when one of the card's NOT THIS IF rules names {@code cardId}. */
    private static boolean namesInNotThisIf(IssueCard card, String cardId) {
        if (card == null || card.notThisIf() == null || cardId == null) return false;
        for (String rule : card.notThisIf()) {
            if (rule != null && namesCard(rule, cardId)) return true;
        }
        return false;
    }

    /** True when {@code text} names the card id as a whole token (NDK-22 is not named by "NDK-224"). */
    private static boolean namesCard(String text, String cardId) {
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(cardId) + "(?![A-Za-z0-9])").matcher(text).find();
    }

    /** Why Stage 1 gave no usable result (matcher fallback flags), or null when it did. */
    public static String matchingFailure(MatchingResult mr) {
        if (mr == null) return "issue matching returned no result";
        List<String> flags = mr.flags() != null ? mr.flags() : List.of();
        for (String f : List.of(IssueMatchingAgent.FLAG_COST_CAP, "matching_failed", "matching_parse_failed")) {
            if (flags.contains(f)) {
                List<String> missing = mr.missingOrDoubtful() != null ? mr.missingOrDoubtful() : List.of();
                String detail = missing.size() > 1 ? missing.get(1) : null;
                return "issue matching failed (" + f + ")" + (detail != null ? ": " + detail : "");
            }
        }
        return null;
    }

    /**
     * Runs the Stage 3 writer for one issue. When the writer fails (twice) or the per-notice budget refuses
     * the call, the issue gets the [[PARTNER]] placeholder and a block flag; no further call is made for it.
     * Returns null only when no writer is configured (the [[PENDING_AI]] marker stays).
     */
    private IssueAiOutput runIssueWriter(MatchedIssue issue, FilledTemplateResult ft, MatchingResult mr, String fullOcrText,
                                         String stage, UUID noticeId, boolean highStakes, List<String> notes,
                                         NoticeCallBudget budget, Set<String> flags) {
        boolean partial = "partial".equalsIgnoreCase(issue.status());
        boolean usePartial = partial && partialDraftingAgent != null;
        if (!usePartial && newIssueDraftingAgent == null) {
            return null;
        }
        String agentKey = highStakes ? NoticeCallBudget.HIGH_STAKES : usePartial ? "partial_drafting" : "new_issue";
        String refusal = budget.refusalReason(agentKey);
        if (refusal != null) {
            notes.add("Issue #" + issue.issueNo() + ": not sent to the AI writer (" + refusal + "); left to the partner.");
            flags.add("block:ai_budget_exhausted");
            return NoticeCallBudget.isCallCountRefusal(refusal) ? IssueAiOutput.partnerPlaceholder(issue) : IssueAiOutput.costCapPlaceholder(issue);
        }
        int refusalsBefore = budget.budgetRefusals();
        List<String> writerNotes = new ArrayList<>();
        IssueAiOutput out = callIssueWriter(issue, ft, mr, fullOcrText, stage, noticeId, highStakes, writerNotes, budget, usePartial);
        if (out == null && budget.budgetRefusals() > refusalsBefore) {
            // the writer's own pre-call check refused the (first or retry) call: worst case over the cap
            String why = budget.lastRefusal();
            notes.add("Issue #" + issue.issueNo() + ": AI writer call not made (" + why + "); left to the partner.");
            flags.add("block:ai_budget_exhausted");
            return NoticeCallBudget.isCallCountRefusal(why) ? IssueAiOutput.partnerPlaceholder(issue) : IssueAiOutput.costCapPlaceholder(issue);
        }
        notes.addAll(writerNotes);
        if (out == null) {
            flags.add("block:ai_draft_failed");
            return IssueAiOutput.partnerPlaceholder(issue);
        }
        return out;
    }

    private IssueAiOutput callIssueWriter(MatchedIssue issue, FilledTemplateResult ft, MatchingResult mr, String fullOcrText,
                                          String stage, UUID noticeId, boolean highStakes, List<String> notes,
                                          NoticeCallBudget budget, boolean usePartial) {
        if (usePartial) {
            ReplyTemplate template = null;
            if (ft != null && ft.cardId() != null && !"NONE".equals(ft.templateId()) && !"MISSING_PERIOD".equals(ft.templateId())) {
                Object period = issue.facts() != null ? issue.facts().get("period") : null;
                template = templateFillService.selectTemplateForCardAndPeriod(ft.cardId(), period != null ? period.toString() : null);
            }
            PartialDraftingResult r = partialDraftingAgent.generatePartialDrafting(issue, mr.notice(), template, stage, noticeId,
                    highStakes, budget);
            if (r == null || r.sections() == null || r.sections().isEmpty()) {
                notes.add("Issue #" + issue.issueNo() + ": " + (highStakes ? "high-stakes" : "partial-drafting")
                        + " writer returned no usable output; left to the partner (write manually).");
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
            // the partner note's "Written by" names the model tier that wrote the issue
            return new IssueAiOutput(issue, highStakes ? "high_stakes" : "partial", frags, nn(r.citations()), nn(r.documents()), r.summaryLine(), null,
                    r.model(), r.promptVersion());
        }

        if (!"none".equalsIgnoreCase(issue.status())) {
            notes.add("Issue #" + issue.issueNo() + " (" + issue.status() + "): no usable template, written by the new-issue writer.");
        }
        NewIssueDraftingResult r = newIssueDraftingAgent.generateNewIssueDraft(issue, mr.notice(), fullOcrText, stage, noticeId,
                highStakes, budget);
        if (r == null) {
            notes.add("Issue #" + issue.issueNo() + ": " + (highStakes ? "high-stakes" : "new-issue")
                    + " writer returned no usable output; left to the partner (write manually).");
            return null;
        }
        List<AiFragment> frags = new ArrayList<>();
        if (r.section04Html() != null && !r.section04Html().isBlank()) frags.add(new AiFragment(4, null, HtmlSanitizer.sanitize(r.section04Html())));
        if (r.section05Html() != null && !r.section05Html().isBlank()) frags.add(new AiFragment(5, null, HtmlSanitizer.sanitize(r.section05Html())));
        if (r.section06Html() != null && !r.section06Html().isBlank()) frags.add(new AiFragment(6, null, HtmlSanitizer.sanitize(r.section06Html())));
        return new IssueAiOutput(issue, highStakes ? "high_stakes" : "new_issue", frags, nn(r.citations()), nn(r.documents()), r.summaryLine(),
                r.strengthNote(), r.model(), r.promptVersion());
    }

    private CitationVerificationResult verifyCitations(List<RawAiCitation> citations, UUID noticeId, NoticeCallBudget budget) {
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
        return citationVerificationAgent.verifyAiCitations(citations, noticeId, budget);
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
        // every [[MISSING: x]] in the draft (code-built and AI-written), plus code markers such as stage templates;
        // de-duplicated by normalised key so "Date of Service" / "date_of_service_of_notice" are listed once,
        // in the wording of the first occurrence
        List<String> missing = new ArrayList<>();
        Matcher mm = MISSING_MARKER.matcher(String.join("\n", sectionsHtml));
        while (mm.find()) missing.add(mm.group(1));
        missing.addAll(assemblyResult.missingMarkers());
        appendMarkerList(sb, "Missing Data Markers", DraftTextCleanup.dedupeMarkers(missing));
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
