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

    private static FilledTemplateResult filled(String cardId, String block04, String block06) {
        return new FilledTemplateResult("TPL-" + cardId, 1, cardId, "Summary for " + cardId + ".",
                List.of(new FilledBlock("BLK-" + cardId + "-04", 4, "<p>" + block04 + "</p>", List.of(), List.of(), List.of()),
                        new FilledBlock("BLK-" + cardId + "-06", 6, "<p>" + block06 + "</p>", List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    @Test
    void defectIssueRendersInSection07_consequentialIssueFollowsItsParentInSection04() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchedIssue demand = new MatchedIssue(1, "full", "demand", List.of("CARD-D"), List.of(), "ITC denied", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0), List.of("3"), List.of(), List.of(), null);
        MatchedIssue consequential = new MatchedIssue(2, "full", "consequential", List.of("CARD-I"), List.of(), "interest on ITC", List.of(),
                Map.of("period", "2022-23", "legal_section", "Section 50(3)"), List.of("4"), List.of(), List.of(), 1);
        MatchedIssue defect = new MatchedIssue(3, "full", "defect", List.of("CARD-P"), List.of(), "no DIN on the notice", List.of(),
                Map.of("period", "2022-23", "legal_section", "Circular 128/47/2019-GST"), List.of("header"), List.of(), List.of(), null);
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(demand, consequential, defect),
                Map.of("3", List.of(1), "4", List.of(1, 2), "5", List.of(3)), List.of(), List.of(), "hash", 10, 10, 0, 0);
        List<FilledTemplateResult> filled = List.of(
                filled("CARD-D", "Demand ground text.", "Demand legal text."),
                filled("CARD-I", "Interest ground text.", "Interest legal text."),
                filled("CARD-P", "No DIN objection text.", "DIN circular legal text."));

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, filled, createTestDraftingInput("M/s ABC", "GSTIN123"), "scn_73");

        String sec04 = result.sections().get(3).bodyHtml();
        assertTrue(sec04.contains("<h3>Issue #1 Grounds of Defence</h3>"), sec04);
        assertTrue(sec04.contains("<h3>Issue #2 (consequential to Issue #1) Grounds of Defence</h3>"), sec04);
        assertTrue(sec04.indexOf("Issue #1 Grounds") < sec04.indexOf("Issue #2 (consequential"), "consequential issue right after its parent");
        assertTrue(sec04.contains("4.1 Demand ground text."), sec04);
        assertTrue(sec04.contains("4.2 Interest ground text."), sec04);
        assertFalse(sec04.contains("Issue #3"), "a defect issue never renders in Section 04: " + sec04);
        assertFalse(sec04.contains("No DIN objection"), sec04);

        String sec06 = result.sections().get(5).bodyHtml();
        assertTrue(sec06.contains("Legal Submissions for Issue #1"));
        assertTrue(sec06.contains("Legal Submissions for Issue #2"));
        assertFalse(sec06.contains("Issue #3"), sec06);
        assertFalse(sec06.contains("DIN circular legal text"), sec06);

        String sec07 = result.sections().get(6).bodyHtml();
        assertEquals("07 Procedural Objections", result.sections().get(6).title());
        assertTrue(sec07.contains("<h3>Issue #3 Procedural Objection</h3>"), sec07);
        assertTrue(sec07.contains("7.1 No DIN objection text."), sec07);
        assertTrue(sec07.contains("7.2 DIN circular legal text."), sec07);
        assertFalse(sec07.contains("[[PARTNER: add procedural objections after checklist review]]"), sec07);

        // Section 05 refers to the paragraph where each issue is answered (7.1 for the defect)
        String sec05 = result.sections().get(4).bodyHtml();
        assertTrue(sec05.contains("<strong>Para 3:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1."), sec05);
        assertTrue(sec05.contains("<strong>Para 5:</strong> Denied. Please refer to the reply to Issue #3 at para 7.1."), sec05);
        assertTrue(sec05.contains("Issues #1 and #2 at paras 4.1 and 4.2"), sec05);
        // Section 01 and 02 point to the right places
        assertTrue(result.sections().get(1).bodyHtml().contains("each answered in Section 04 (procedural objections in Section 07)"));
        assertFalse(result.sections().get(0).bodyHtml().contains("{{ref:"));

        // the partner note keeps the checklist options and names the kinds
        String sec13 = result.sections().get(12).bodyHtml();
        assertTrue(sec13.contains("Procedural Objection Options (Partner to Select)"), sec13);
        assertTrue(sec13.contains("Issue #3: Match Status=full, Cards=[CARD-P], Template=TPL-CARD-P (v1), Written by=template (code), Kind=defect (rendered in Section 07)"), sec13);
        assertTrue(sec13.contains("Kind=consequential (parent Issue #1)"), sec13);
        assertFalse(result.flags().contains("pending_ai_writer"), result.flags().toString());
        assertTrue(result.pendingAiMarkers().isEmpty());
    }

    @Test
    void defectIssueWrittenByAi_rendersItsSection04And06TextUnderSection07() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchedIssue demand = new MatchedIssue(1, "full", "demand", List.of("CARD-D"), List.of(), "ITC denied", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0), List.of("3"), List.of(), List.of(), null);
        MatchedIssue defect = new MatchedIssue(2, "none", "defect", List.of(), List.of(), "notice is time-barred", List.of(),
                Map.of("period", "2022-23", "legal_section", "Section 73(10)"), List.of("header"), List.of(), List.of(), null);
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(demand, defect), Map.of("3", List.of(1)),
                List.of(), List.of(), "hash", 10, 10, 0, 0);
        List<FilledTemplateResult> filled = List.of(filled("CARD-D", "Demand ground text.", "Demand legal text."),
                new FilledTemplateResult("NONE", 0, null, null, List.of(), List.of(), List.of(), List.of("no_template_available"), List.of(), List.of(), false));
        Map<Integer, ReplyAssemblyService.AiIssueContent> ai = Map.of(2, new ReplyAssemblyService.AiIssueContent(2, "new_issue",
                List.of(new ReplyAssemblyService.AiFragment(4, null, "<p>The notice is beyond the Section 73(10) time limit.</p>"),
                        new ReplyAssemblyService.AiFragment(6, null, "<p>Limitation submission.</p>")),
                List.of(), "Time-barred notice."));

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, filled, createTestDraftingInput("M/s ABC", "GSTIN123"),
                "scn_73", ai, null, List.of());

        String sec07 = result.sections().get(6).bodyHtml();
        assertTrue(sec07.contains("<h3>Issue #2 Procedural Objection</h3>"), sec07);
        assertTrue(sec07.contains("7.1 The notice is beyond the Section 73(10) time limit."), sec07);
        assertTrue(sec07.contains("7.2 Limitation submission."), sec07);
        assertFalse(result.sections().get(3).bodyHtml().contains("Issue #2"), "not in Section 04");
        assertFalse(result.sections().get(5).bodyHtml().contains("Issue #2"), "not in Section 06");
        assertTrue(result.sections().get(0).bodyHtml().contains("Time-barred notice."));

        // without AI text the defect issue keeps a PENDING_AI marker, in Section 07
        AssembledReplyResult pending = assemblyService.assembleReply(matchingResult, filled, createTestDraftingInput("M/s ABC", "GSTIN123"), "scn_73");
        assertTrue(pending.sections().get(6).bodyHtml().contains("7.1 [[PENDING_AI: Issue 2"), pending.sections().get(6).bodyHtml());
        assertTrue(pending.pendingAiMarkers().contains("PENDING_AI: Issue 2"));
        assertFalse(pending.sections().get(3).bodyHtml().contains("Issue 2"));
    }

    @Test
    void withoutDefectIssues_section07KeepsThePartnerPlaceholder() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo,
                List.of(new MatchedIssue(1, "full", List.of("CARD-D"), "Why", List.of(), Map.of("period", "2022-23"), List.of(), List.of())),
                Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(filled("CARD-D", "Ground.", "Law.")),
                createTestDraftingInput("M/s ABC", "GSTIN123"), "scn_73");
        assertEquals("<!-- para:", result.sections().get(6).bodyHtml().substring(0, 10));
        assertTrue(result.sections().get(6).bodyHtml().contains("[[PARTNER: add procedural objections after checklist review]]"));
        assertTrue(result.sections().get(1).bodyHtml().contains("each answered in Section 04 and paragraph-wise in Section 05."));
    }

    @Test
    void partnerNoteExplainsTemplateWindowAndPeriodFlags() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-001", "DIN-001", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(
                new MatchedIssue(1, "full", List.of("CARD-N"), "Why", List.of(), Map.of("period", "FY 2019-20"), List.of(), List.of()),
                new MatchedIssue(2, "full", List.of("CARD-L"), "Why", List.of(), Map.of("period", "the audit period"), List.of(), List.of())),
                Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);
        FilledTemplateResult nearest = new FilledTemplateResult("TPL-CARD-N", 2, "CARD-N", "S.",
                List.of(new FilledBlock("B1", 4, "<p>Ground.</p>", List.of(), List.of(), List.of())), List.of(), List.of(),
                List.of(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH), List.of(), List.of(), false,
                Map.of(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH, Map.of("card_id", "CARD-N", "period_start", "2019-04-01",
                        "period_end", "2020-03-31", "template_effective_from", "2021-01-01", "template_effective_to", "")));
        FilledTemplateResult latest = new FilledTemplateResult("TPL-CARD-L", 3, "CARD-L", "S.",
                List.of(new FilledBlock("B2", 4, "<p>Ground.</p>", List.of(), List.of(), List.of())), List.of(), List.of(),
                List.of(TemplateFillService.FLAG_PERIOD_NOT_NORMALISED), List.of(), List.of(), false);

        AssembledReplyResult result = assemblyService.assembleReply(matchingResult, List.of(nearest, latest),
                createTestDraftingInput("M/s ABC", "GSTIN123"), "scn_73");

        String sec13 = result.sections().get(12).bodyHtml();
        assertTrue(sec13.contains("Note=No template version of card CARD-N covers the period 2019-04-01 to 2020-03-31; nearest version v2 (2021-01-01 to open) used"), sec13);
        assertTrue(sec13.contains("Note=Period 'the audit period' could not be read as dates; latest template version v3 used"), sec13);
        assertTrue(result.sections().get(3).bodyHtml().contains("4.1 Ground."));
        assertTrue(result.sections().get(3).bodyHtml().contains("4.2 Ground."));
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

