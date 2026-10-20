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
 * The catalogue shortlist inside the v7 pipeline (every external call mocked): a substantive ground the shortlist
 * could not place triggers ONE more matching call on the full catalogue, whose result is used; an unmatched ground
 * without substance does not. Also: the stage correction runs only when a CatalogueService is set (the live runner
 * builds the pipeline by hand and must call the setter).
 */
class V7ShortlistWideningTest {

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

    private static Shortlist keywordShortlist() {
        return new Shortlist(List.of(card("T-1", List.of("scn_73"), List.of()), card("T-2", List.of("scn_73"), List.of()),
                card("T-3", List.of("generic_scn"), List.of())), CardShortlister.LEVEL_KEYWORD, 12, 7, 2, 1, List.of("scn_73"));
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
    void substantiveMissWidensOnceToTheFullCatalogueAndUsesThatResult() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue unmatched = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "no shown card fits", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("1"), List.of(),
                List.of(), null);
        MatchedIssue matched = new MatchedIssue(1, "full", "demand", List.of("CARD-A"), List.of(), "fits", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("1"), List.of(),
                List.of(), null);
        Shortlist full = CardShortlister.full(Collections.nCopies(12, card("T-9", List.of("other"), List.of())));
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(result(unmatched, keywordShortlist()));
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any(), eq(true))).thenReturn(result(matched, full));
        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);

        DraftingPipelineResult r = pipeline(matcher, newIssue).runPipeline(input());

        verify(matcher, times(1)).matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any());
        verify(matcher, times(1)).matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any(), eq(true));
        // the widened result is used: the ground is a full match filled from the template, no writer call
        verify(newIssue, never()).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
        String s13 = r.draft().sections().stream().filter(s -> s.num() == 13).findFirst().orElseThrow().bodyHtml();
        assertTrue(s13.contains("Issue #1: Match Status=full, Cards=[CARD-A]"), s13);
        assertTrue(s13.contains("Catalogue shortlist: 3 of 12 cards shown to the matcher (level keyword; stage 7, keyword 2, siblings 1)."), s13);
        assertTrue(s13.contains("Matching widened: issue(s) [1] matched no card among the 3 shortlisted, so matching ran again on all 12 cards"), s13);

        DraftCheckResult shortlistFlag = flag(r, V7DraftingPipelineService.FLAG_SHORTLIST).orElseThrow();
        assertEquals("info", shortlistFlag.severity());
        assertEquals(3, shortlistFlag.details().get("size"));
        assertEquals("keyword", shortlistFlag.details().get("level"));
        assertEquals(7, shortlistFlag.details().get("stage"));
        DraftCheckResult widened = flag(r, V7DraftingPipelineService.FLAG_SHORTLIST_WIDENED).orElseThrow();
        assertEquals(3, widened.details().get("shortlist_size"));
        assertEquals(12, widened.details().get("full_size"));
        assertEquals(List.of(1), widened.details().get("unmatched_issues"));
        assertEquals("3 cards (level keyword; stage 7, keyword 2, siblings 1); widened to 12 cards for issue(s) [1]",
                V7LivePipelineRunnerTest.shortlistLine(r.checkResults()));
    }

    @Test
    void groundWithoutSubstanceDoesNotWiden() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue corrigendum = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "corrigendum to the notice date",
                List.of(), Map.of("period", "2022-23"), List.of("1"), List.of(), List.of(), null);
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(result(corrigendum, keywordShortlist()));
        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);

        DraftingPipelineResult r = pipeline(matcher, newIssue).runPipeline(input());

        verify(matcher, never()).matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any(), anyBoolean());
        assertTrue(flag(r, V7DraftingPipelineService.FLAG_SHORTLIST).isPresent());
        assertTrue(flag(r, V7DraftingPipelineService.FLAG_SHORTLIST_WIDENED).isEmpty());
        assertEquals("3 cards (level keyword; stage 7, keyword 2, siblings 1)", V7LivePipelineRunnerTest.shortlistLine(r.checkResults()));
    }

    @Test
    void failedWideningKeepsTheShortlistResult() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue unmatched = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "no shown card fits", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0, "legal_section", "Section 16(2)(aa)"), List.of("1"), List.of(),
                List.of(), null);
        MatchingResult first = result(unmatched, keywordShortlist());
        MatchingResult refused = new MatchingResult(INFO, List.of(), Map.of(), List.of(IssueMatchingAgent.FLAG_COST_CAP),
                List.of(IssueMatchingAgent.FLAG_COST_CAP), "hash", 0, 0, 0, 0);
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any(), eq(true))).thenReturn(refused);
        List<String> notes = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();

        MatchingResult kept = pipeline(matcher, null).widenOnShortlistMiss(first, TEXT, "scn_73", UUID.randomUUID(), UUID.randomUUID(),
                null, notes, flags, details);

        assertSame(first, kept);
        assertEquals(Set.of(V7DraftingPipelineService.FLAG_SHORTLIST), flags);
        assertTrue(notes.stream().anyMatch(n -> n.contains("the shortlist result is kept")), notes.toString());
    }

    @Test
    void noShortlistNoFlagAndNoSecondCall() {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedIssue unmatched = new MatchedIssue(1, "none", "demand", List.of(), List.of(), "w", List.of(),
                Map.of("amount", 1.0, "legal_section", "Section 16"), List.of(), List.of(), List.of(), null);
        MatchingResult mr = result(unmatched, null);
        Set<String> flags = new LinkedHashSet<>();
        assertSame(mr, pipeline(matcher, null).widenOnShortlistMiss(mr, TEXT, "scn_73", null, null, null, new ArrayList<>(), flags,
                new LinkedHashMap<>()));
        assertTrue(flags.isEmpty());
        verifyNoInteractions(matcher);
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
