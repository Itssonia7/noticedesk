package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.CardShortlister;
import com.noticedesk.api.service.matching.CardShortlister.Shortlist;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The catalogue shortlist inside the v7 pipeline (every external call mocked): the shortlist gets its flag and partner
 * note; an issue the shortlist could not place (status none on a substantive ground) goes straight to the new-issue
 * writer, with NO second matching call; a title pass that could not be used raises a warn flag. Also: the stage
 * correction runs only when a CatalogueService is set (the live runner builds the pipeline by hand and must call the
 * setter).
 */
class V7ShortlistInPipelineTest {

    private static final String TEXT = """
            OFFICE OF THE DEPUTY COMMISSIONER OF STATE TAX
            Show Cause Notice under Section 73 of the SGST Act, 2017
            Reference No: SCN/TEST/2024-25/1
            Date: 02.06.2024
            Financial Year: 2022-23

            1. Input tax credit of Rs. 1,00,000/- is not reflected in GSTR-2B (Section 16(2)(aa)).
            2. Reply within 30 days.

            Digitally signed by TEST OFFICER
            """;

    private final ObjectMapper om = new ObjectMapper();

    private static IssueCard card(String id, List<String> stages, List<String> notThisIf) {
        return new IssueCard(id, 1, "Title " + id, "summary", List.of(), null, null, stages, List.of("Section 16(2)(aa)"),
                notThisIf, null, "active", null, null, null);
    }

    private static final MatchedNoticeInfo INFO = new MatchedNoticeInfo("SCN/TEST/2024-25/1", null, "02.06.2024", "within 30 days",
            "2022-23", "2022-23", 100000.0, "Deputy Commissioner of State Tax");

    private static MatchingResult result(MatchedIssue issue, Shortlist shortlist) {
        return new MatchingResult(INFO, List.of(issue), Map.of("1", List.of(1)), List.of(), List.of(), "hash", 1, 1, 0, 0,
                Map.of(), Map.of(), shortlist);
    }

    private static final List<IssueCard> THREE = List.of(card("T-1", List.of("scn_73"), List.of()),
            card("T-2", List.of("scn_73"), List.of()), card("T-3", List.of("generic_scn"), List.of()));

    /** 3 of 12 cards, stage pool 7: labels T-1 / T-2, AI T-3. */
    private static Shortlist keywordShortlist() {
        return keywordShortlist(CardShortlister.AI_USED);
    }

    private static Shortlist keywordShortlist(String aiStatus) {
        return new Shortlist(THREE, List.of(), CardShortlister.LEVEL_HYBRID, 12, 7, Set.of("T-1", "T-2"),
                CardShortlister.AI_USED.equals(aiStatus) ? Set.of("T-3") : Set.of(), 0, aiStatus, List.of("scn_73"));
    }

    private V7DraftingPipelineService pipeline(IssueMatchingAgent matcher, NewIssueDraftingAgent newIssue) {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-A", 1, "CARD-A",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-A-04", 4, "<p>Template ground for {{client.legal_name}}.</p>", List.of(), null)),
                List.of("client.legal_name"), List.of("Doc A"), "Template A summary.", List.of(), null, null, null));
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer: drop the proceedings under {{notice.section}}.</p>", "15", "<p>Checklist.</p>"), null, null, null));
        return new V7DraftingPipelineService(matcher, null, om, fill, assembly, new DraftCheckService(null),
                mock(PartialDraftingAgent.class), newIssue, mock(HighStakesDraftingAgent.class), null, null);
    }

    private static DraftingInput input() {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Test Client Pvt Ltd", null, null, null,
                "TESTGSTIN", "Test State", "CGST Act", "2022-23", null,
                Map.of("document_type", "scn_73", "section", "Section 73"), Map.of(), TEXT, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static Optional<DraftCheckResult> flag(DraftingPipelineResult r, String flag) {
        return r.checkResults().stream().filter(c -> c.details() != null && flag.equals(c.details().get("flag"))).findFirst();
    }

    @Test
    void substantiveMissGoesToTheWriterWithoutASecondMatchingCall() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue unmatched = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "no shown card fits", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("1"), List.of(),
                List.of(), null);
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(result(unmatched, keywordShortlist()));
        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);
        when(newIssue.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(1, "<p>Original ground.</p>", "", "<p>Submissions.</p>", List.of(),
                        "note", List.of(), "summary", "test-model", NewIssueDraftingAgent.PROMPT_VERSION));

        DraftingPipelineResult r = pipeline(matcher, newIssue).runPipeline(input());

        verify(matcher, times(1)).matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any());
        verifyNoMoreInteractions(matcher);
        verify(newIssue, times(1)).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
        String s13 = r.draft().sections().stream().filter(s -> s.num() == 13).findFirst().orElseThrow().bodyHtml();
        assertTrue(s13.contains("Catalogue shortlist: 3 of 12 cards shown to the matcher (pool 7; labels 2, ai 1, siblings 0; titles-only 0)"), s13);
        assertFalse(s13.contains("widen"), s13);

        DraftCheckResult shortlistFlag = flag(r, V7DraftingPipelineService.FLAG_SHORTLIST).orElseThrow();
        assertEquals("info", shortlistFlag.severity());
        assertEquals(3, shortlistFlag.details().get("size"));
        assertEquals("hybrid", shortlistFlag.details().get("level"));
        assertEquals(7, shortlistFlag.details().get("pool"));
        assertEquals(2, shortlistFlag.details().get("labels"));
        assertEquals(1, shortlistFlag.details().get("ai"));
        assertTrue(r.checkResults().stream().noneMatch(c -> c.details() != null
                && String.valueOf(c.details().get("flag")).contains("widen")), "no widening flag");
        assertTrue(flag(r, V7DraftingPipelineService.FLAG_SHORTLIST_AI_SKIPPED).isEmpty());
        assertEquals("3 cards (pool 7; labels 2, ai 1, siblings 0; titles-only 0)", V7LivePipelineRunnerTest.shortlistLine(r.checkResults()));
    }

    @Test
    void aiTitlePassSkippedRaisesAWarnFlag() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue matched = new MatchedIssue(1, "full", "demand", List.of("CARD-A"), List.of(), "fits", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("1"), List.of(),
                List.of(), null);
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any()))
                .thenReturn(result(matched, keywordShortlist(CardShortlister.AI_SKIPPED)));

        DraftingPipelineResult r = pipeline(matcher, mock(NewIssueDraftingAgent.class)).runPipeline(input());

        assertEquals("warn", flag(r, V7DraftingPipelineService.FLAG_SHORTLIST_AI_SKIPPED).orElseThrow().severity());
        assertEquals("3 cards (pool 7; labels 2, ai skipped, siblings 0; titles-only 0)", V7LivePipelineRunnerTest.shortlistLine(r.checkResults()));
    }

    @Test
    void runnerMatchedIssuesLine() {
        assertEquals("none", V7LivePipelineRunnerTest.matchedIssuesLine(List.of()));
        assertEquals("#1 full [CARD-A, CARD-B]; #2 none []", V7LivePipelineRunnerTest.matchedIssuesLine(List.of(
                Map.of("issue_no", 1, "status", "full", "card_ids", "[\"CARD-A\", \"CARD-B\"]"),
                Map.of("issue_no", 2, "status", "none", "card_ids", "[]"))));
    }

    @Test
    void noShortlistNoFlag() {
        MatchedIssue unmatched = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "w", List.of(),
                Map.of("amount", 1.0, "legal_section", "Section 16"), List.of(), List.of(), List.of(), null);
        Set<String> flags = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        pipeline(mock(IssueMatchingAgent.class), null).recordShortlist(result(unmatched, null), notes, flags, new LinkedHashMap<>());
        assertTrue(flags.isEmpty());
        assertTrue(notes.isEmpty());
    }

    // ------------------------------------------------------------------ stage correction needs the catalogue

    @Test
    void stageCorrectionIsActiveOnlyWhenACatalogueServiceIsSet() {
        IssueCard reply = card("T-225", List.of("refund"), List.of("Input is an RFD-06 order: use T-224 (appeal)."));
        IssueCard appeal = card("T-224", List.of("order_appeal"), List.of("Input is an RFD-08 notice: use T-225."));
        CatalogueService catalogue = mock(CatalogueService.class);
        when(catalogue.cardsById()).thenReturn(CatalogueService.byId(List.of(appeal, reply)));
        List<MatchedIssue> issues = List.of(new MatchedIssue(1, "full", "demand", List.of("T-225"), List.of(), "w", List.of(),
                Map.of(), List.of(), List.of()));
        String order = "ORDER rejecting the refund application in FORM GST RFD-06";

        V7DraftingPipelineService withoutCatalogue = pipeline(mock(IssueMatchingAgent.class), null);
        assertSame(issues, withoutCatalogue.applyStageCorrection(issues, "RFD-06", order, null, new ArrayList<>(),
                new LinkedHashSet<>(), new LinkedHashMap<>()), "no CatalogueService: the correction silently does nothing");

        V7DraftingPipelineService withCatalogue = pipeline(mock(IssueMatchingAgent.class), null);
        withCatalogue.setCatalogueService(catalogue);
        Set<String> flags = new LinkedHashSet<>();
        List<MatchedIssue> corrected = withCatalogue.applyStageCorrection(issues, "RFD-06", order, null, new ArrayList<>(), flags,
                new LinkedHashMap<>());
        assertEquals("T-224", corrected.get(0).primaryCardId());
        assertEquals(List.of("T-225"), corrected.get(0).alternateCardIds());
        assertTrue(flags.contains(V7DraftingPipelineService.FLAG_STAGE_CORRECTED));
    }
}
