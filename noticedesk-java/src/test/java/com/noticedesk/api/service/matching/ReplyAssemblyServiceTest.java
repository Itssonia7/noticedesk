package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.TemplateFillService.FilledBlock;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplyAssemblyServiceTest {

    private ReplyAssemblyService assemblyService;

    @BeforeEach
    void setUp() {
        assemblyService = new ReplyAssemblyService(null, null, new ObjectMapper());
    }

    private DraftingInput createTestDraftingInput(String legalName, String gstin) {
        return new DraftingInput(
                UUID.randomUUID(), "formal", "Instructions", legalName, "PAN123", "Company", "Regular", gstin, "Maharashtra", "CGST Act", "2022-23", "2023-24",
                Map.of("issue_date", "2023-10-15"), Map.of(), "OCR", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );
    }

    @Test
    void testExact15SectionTitles() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(
                noticeInfo,
                List.of(new MatchedIssue(1, "full", List.of("CARD-006"), "Why", List.of(), Map.of("period", "2022-23", "amount", 100000.0), List.of(), List.of())),
                Map.of("record", List.of(1), "issue", List.of(2), "demand", List.of(3)),
                List.of(), List.of(), "hash", 10, 10, 0, 0
        );

        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");
        StageTemplate stageTpl = new StageTemplate(
                "scn_73", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                Map.of("08", "<p>Cross exam</p>", "12", "<p>Prayer text</p>", "14", "<p>Client summary text</p>", "15", "<p>Checklist text</p>"),
                null, null, null
        );
        assemblyService.registerInMemoryStageTemplate(stageTpl);

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(), input, "scn_73");

        assertEquals(15, result.sections().size());

        List<String> expectedTitles = List.of(
                "01 Executive Summary", "02 Notice Understanding", "03 Factual Background",
                "04 Issue-wise Response", "05 Para-wise Reply", "06 Legal Submissions",
                "07 Procedural Objections", "08 Cross-Examination Request",
                "09 Related Context from Other Registrations", "10 Documents Enclosed",
                "11 Annexure Index", "12 Prayer", "13 Internal Partner Note",
                "14 Client Summary", "15 Filing Checklist"
        );

        for (int i = 0; i < 15; i++) {
            assertEquals(i + 1, result.sections().get(i).num());
            assertEquals(expectedTitles.get(i), result.sections().get(i).title());
        }
    }

    @Test
    void testSections08And09NotApplicableWhenNotReliedUpon() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(), input, "scn_73");

        AssembledSection sec08 = result.sections().get(7); // 08 Cross-Examination Request
        AssembledSection sec09 = result.sections().get(8); // 09 Related Context

        assertTrue(sec08.bodyHtml().contains("Not applicable."));
        assertTrue(sec09.bodyHtml().contains("Not applicable."));
    }

    @Test
    void testMissingStageTemplateGeneratesMissingMarkerAndFlag() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(), input, "unknown_stage_99");

        AssembledSection sec12 = result.sections().get(11); // 12 Prayer
        assertTrue(sec12.bodyHtml().contains("[[MISSING: stage template for unknown_stage_99]]"));
        assertTrue(result.flags().contains("missing_stage_template_unknown_stage_99"));
    }

    @Test
    void testPartialOrNoMatchIssueGeneratesPendingAiMarker() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchedIssue partialIssue = new MatchedIssue(2, "partial", List.of("CARD-006"), "Partial fit", List.of(), Map.of("period", "2022-23"), List.of(), List.of());
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(partialIssue), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(), input, "scn_73");

        AssembledSection sec04 = result.sections().get(3); // 04 Issue-wise Response
        assertTrue(sec04.bodyHtml().contains("[[PENDING_AI: Issue 2 — partial/new, written in Stage 3]]"));
        assertTrue(result.flags().contains("pending_ai_writer"));
        assertTrue(result.pendingAiMarkers().contains("PENDING_AI: Issue 2"));
    }
}

