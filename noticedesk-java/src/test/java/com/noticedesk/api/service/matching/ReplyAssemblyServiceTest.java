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
    void partnerNote_listsTheClientFactsToConfirm_perIssue_onlyWhenPresent() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchedIssue withFacts = new MatchedIssue(1, "full", "demand", List.of("CARD-006"), List.of(), "Why", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0), List.of(), List.of(),
                List.of("No consignment note was issued by the transporter", "Recipient holds the invoices and bank proof of payment"));
        MatchedIssue withoutFacts = new MatchedIssue(2, "partial", "demand", List.of("CARD-006"), List.of(), "Partial fit", List.of(),
                Map.of("period", "2022-23"), List.of(), List.of(), List.of());
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(withFacts, withoutFacts), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(), input, "scn_73");

        AssembledSection sec13 = result.sections().get(12); // 13 Internal Partner Note
        assertEquals("13 Internal Partner Note", sec13.title());
        String html = sec13.bodyHtml();
        int issue1 = html.indexOf("<li>Issue #1:");
        int issue2 = html.indexOf("<li>Issue #2:");
        assertTrue(issue1 >= 0 && issue2 > issue1, html);
        String li1 = html.substring(issue1, html.indexOf("</li>", issue1));
        String li2 = html.substring(issue2, html.indexOf("</li>", issue2));
        assertTrue(li1.contains("Written by="), li1);
        assertTrue(li1.contains("<br/>Confirm with client: No consignment note was issued by the transporter; "
                + "Recipient holds the invoices and bank proof of payment"), li1);
        assertFalse(li2.contains("Confirm with client"), "an issue without client facts gets no line: " + li2);
        assertEquals(1, html.split("Confirm with client", -1).length - 1, "exactly one line, for the one issue with facts");

        // the line is internal: it stays out of the client-facing sections
        for (int s = 0; s < result.sections().size(); s++) {
            if (s == 12) continue;
            assertFalse(result.sections().get(s).bodyHtml().contains("Confirm with client"), result.sections().get(s).title());
        }
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

