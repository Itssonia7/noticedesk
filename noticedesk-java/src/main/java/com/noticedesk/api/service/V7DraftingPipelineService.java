package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Service for Architecture v7 Drafting Pipeline.
 * Stage 1: Runs IssueMatchingAgent, persists issue_matches rows, and halts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class V7DraftingPipelineService {

    private final IssueMatchingAgent issueMatchingAgent;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DraftingPipelineResult runPipeline(DraftingInput input) {
        log.info("V7DraftingPipelineService starting Stage 1 issue matching execution");

        UUID tenantId = UUID.randomUUID(); // Resolved from context or job
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

        // Persist issue_matches rows to database
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
        throw new UnsupportedOperationException("v7 drafting after matching not built yet");
    }
}
