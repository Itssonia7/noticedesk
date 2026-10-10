package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for Architecture v7 Drafting Pipeline.
 * Stage 1: Issue matching & catalog lookup.
 * Stage 2: Code-based template filling, 15-section reply assembly, deterministic code checks.
 */
@Service
@RequiredArgsConstructor
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

    public V7DraftingPipelineService(
            IssueMatchingAgent issueMatchingAgent,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TemplateFillService templateFillService,
            ReplyAssemblyService replyAssemblyService,
            DraftCheckService draftCheckService) {
        this(issueMatchingAgent, jdbc, objectMapper, templateFillService, replyAssemblyService, draftCheckService, null, null, null, null);
    }

    public DraftingPipelineResult runPipeline(DraftingInput input) {
        log.info("V7DraftingPipelineService starting Stage 1 issue matching execution");

        UUID tenantId = UUID.randomUUID();
        UUID noticeId = UUID.randomUUID();
        if (input != null && input.notice() != null && input.notice().get("notice_id") != null) {
            try {
                noticeId = UUID.fromString(input.notice().get("notice_id").toString());
            } catch (Exception ignored) {}
        }

        String noticeType = "scn_73";
        if (input != null && input.notice() != null && input.notice().get("document_type") != null) {
            noticeType = input.notice().get("document_type").toString();
        }

        String fullOcrText = input != null ? input.noticeOcrExcerpt() : "";
        MatchingResult matchingResult = issueMatchingAgent.matchNoticeWithOcrText(fullOcrText, noticeType, tenantId, noticeId);

        // 1. Persist issue_matches rows to database
        if (jdbc != null && matchingResult != null && matchingResult.issues() != null) {
            for (MatchedIssue issue : matchingResult.issues()) {
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

        log.info("Stage 1 Issue Matching complete: matched {} issues", matchingResult != null ? matchingResult.issues().size() : 0);

        // 2. Stage 2: Code-based Template Filling
        List<FilledTemplateResult> filledTemplates = new ArrayList<>();
        if (matchingResult != null && matchingResult.issues() != null) {
            for (MatchedIssue issue : matchingResult.issues()) {
                FilledTemplateResult ft = templateFillService.fillTemplateForIssue(issue, matchingResult.notice(), input);
                filledTemplates.add(ft);
            }
        }

        // 3. Stage 2: 15-Section Reply Assembly
        AssembledReplyResult assemblyResult = replyAssemblyService.assembleReply(matchingResult, filledTemplates, input, noticeType);

        // 4. Stage 2: Deterministic Code Checks
        List<DraftCheckResult> checkResults = draftCheckService.runAllChecks(matchingResult, assemblyResult);

        // 5. Construct GeneratedDraft DTO with enriched Section 13
        StringBuilder checkSb = new StringBuilder();
        checkSb.append("<p><strong>Code Quality Check Results & Flags:</strong></p><table>");
        checkSb.append("<tr><th>Check Name</th><th>Status</th><th>Severity</th><th>Details</th></tr>");
        for (DraftCheckResult cr : checkResults) {
            checkSb.append("<tr><td>").append(cr.checkName())
                   .append("</td><td>").append(cr.passed() ? "PASS" : "FAIL")
                   .append("</td><td>").append(cr.severity())
                   .append("</td><td>").append(cr.details() != null ? cr.details().toString() : "").append("</td></tr>");
        }
        checkSb.append("</table>");

        if (assemblyResult.missingMarkers() != null && !assemblyResult.missingMarkers().isEmpty()) {
            checkSb.append("<p><strong>Missing Data Markers:</strong></p><ul>");
            for (String m : assemblyResult.missingMarkers()) {
                checkSb.append("<li>").append(m).append(" (severity: block)</li>");
            }
            checkSb.append("</ul>");
        } else {
            checkSb.append("<p><strong>Missing Data Markers:</strong> None</p>");
        }

        if (assemblyResult.pendingAiMarkers() != null && !assemblyResult.pendingAiMarkers().isEmpty()) {
            checkSb.append("<p><strong>Pending AI Markers:</strong></p><ul>");
            for (String p : assemblyResult.pendingAiMarkers()) {
                checkSb.append("<li>").append(p).append(" (severity: block)</li>");
            }
            checkSb.append("</ul>");
        } else {
            checkSb.append("<p><strong>Pending AI Markers:</strong> None</p>");
        }

        List<DraftSection> draftSections = new ArrayList<>();
        for (AssembledSection s : assemblyResult.sections()) {
            String bodyHtml = s.bodyHtml();
            if (s.num() == 13 && bodyHtml.contains("<!-- CHECK_RESULTS_PLACEHOLDER -->")) {
                bodyHtml = bodyHtml.replace("<!-- CHECK_RESULTS_PLACEHOLDER -->", checkSb.toString());
            }
            draftSections.add(new DraftSection(s.num(), s.title(), bodyHtml));
        }

        GeneratedDraft generatedDraft = new GeneratedDraft(
                draftSections,
                "v7 Stage 2 Assembled Draft",
                "Firm Standard Disclaimers Apply",
                "v7-assembled",
                "none",
                "formal",
                matchingResult != null ? matchingResult.outputTokens() : 0,
                draftSections.size()
        );

        log.info("Stage 2 Reply Assembly complete: assembled {} sections and executed {} code checks",
                draftSections.size(), checkResults.size());

        return new DraftingPipelineResult(
                generatedDraft,
                List.of(),
                Map.of("total_citations", 0),
                0, 0, 0,
                matchingResult != null ? matchingResult.inputTokens() : 0,
                matchingResult != null ? matchingResult.outputTokens() : 0,
                "end_turn",
                assemblyResult.paragraphSourceMap(),
                checkResults
        );
    }
}
