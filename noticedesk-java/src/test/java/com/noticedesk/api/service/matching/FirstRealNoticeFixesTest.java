package com.noticedesk.api.service.matching;

import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Code fixes after the first real notice run: near-duplicate documents merged (Sections 10 / 11 / 14), statutory
 * paragraphs found for the "Noted" reply, and the defect-check cards always in the shortlist.
 */
class FirstRealNoticeFixesTest {

    // ------------------------------------------------------------------ documents

    /** The documents list of the RFD-08 run (three grounds, two writers each listing their own wording). */
    static final List<String> RUN_DOCUMENTS = List.of(
            "Copy of the refund application in Form GST RFD-01",
            "Refund application in FORM GST RFD-01 with all annexures",
            "Copy of the acknowledgement in Form GST RFD-02",
            "Acknowledgement in FORM RFD-02",
            "Copies of purchase invoices issued by the suppliers",
            "Purchase invoices of the suppliers",
            "Tax invoices issued by the suppliers listed in the notice",
            "GSTR-2B for January 2026",
            "Copy of GSTR-2B for January 2026 showing the supplier invoices",
            "GSTR-3B return for January 2026",
            "Copy of GSTR-3B for January 2026",
            "GSTR-1 for January 2026",
            "Bank statements evidencing payment to the suppliers",
            "Bank statement showing payments to the suppliers",
            "E-way bills for the inward supplies",
            "Copies of e-way bills for inward supplies",
            "Ledger of the suppliers in the books of the noticee",
            "Supplier ledger accounts in the books of the noticee",
            "Undertaking under Rule 89(2)(l)",
            "Undertaking under Rule 89(2)(l) of the CGST Rules",
            "Computation of refund under Rule 89(5)",
            "Statement of computation of refund under Rule 89(5) of the CGST Rules");

    @Test
    void the22LineDocumentsListOfTheRunCollapsesToAboutTwelve() {
        assertEquals(22, RUN_DOCUMENTS.size());
        List<String> merged = DraftTextCleanup.mergeNearDuplicateDocuments(DraftTextCleanup.dedupeDocuments(RUN_DOCUMENTS));
        assertTrue(merged.size() >= 11 && merged.size() <= 13, merged.size() + " lines: " + merged);
        // the longer wording is kept
        assertTrue(merged.contains("Refund application in FORM GST RFD-01 with all annexures"), merged.toString());
        assertTrue(merged.contains("Statement of computation of refund under Rule 89(5) of the CGST Rules"), merged.toString());
        assertTrue(merged.contains("Undertaking under Rule 89(2)(l) of the CGST Rules"), merged.toString());
        // different returns stay apart
        assertEquals(1, merged.stream().filter(d -> d.contains("GSTR-2B")).count(), merged.toString());
        assertEquals(1, merged.stream().filter(d -> d.contains("GSTR-3B")).count(), merged.toString());
        assertEquals(1, merged.stream().filter(d -> d.contains("GSTR-1 ")).count(), merged.toString());
    }

    @Test
    void nearDuplicateRules() {
        assertTrue(DraftTextCleanup.sameDocument("Copy of the acknowledgement in Form GST RFD-02", "Acknowledgement in FORM RFD-02"));
        assertTrue(DraftTextCleanup.sameDocument("GSTR-3B for January 2026", "Copies of GSTR-3B for January 2026"), "containment");
        assertFalse(DraftTextCleanup.sameDocument("GSTR-1 for January 2026", "GSTR-3B for January 2026"));
        assertFalse(DraftTextCleanup.sameDocument("Bank statements", "Purchase invoices"));
        assertEquals(List.of("Copies of e-way bills for inward supplies"),
                DraftTextCleanup.mergeNearDuplicateDocuments(List.of("E-way bills for inward supplies", "Copies of e-way bills for inward supplies")),
                "the longer line is kept, in the first line's place");
    }

    // ------------------------------------------------------------------ statutory paragraphs

    @Test
    void statutoryParagraphsAreFound() {
        String text = """
                1. The applicant filed the refund application for the period JAN-2026.
                Contravention of the Law:
                2. Section 54(1) of the CGST Act, 2017 provides that any person claiming refund of any tax may make an application.
                3. (A) Section 16(2) of the CGST Act, 2017 reads as under.
                4. "(1) Every registered person shall, subject to such conditions, be entitled to take credit of input tax."
                5. On verification it is observed that the suppliers have not filed their returns; the claim is not admissible.
                6. Rule 89(5) of the CGST Rules, 2017 provides the formula for the refund on account of inverted duty structure.
                Provisions
                7. The relevant extract is reproduced for ready reference.
                8. Statutory provisions: Section 155 of the CGST Act places the burden of proof on the claimant.
                9. You are hereby directed to show cause within 15 days.
                """;
        assertEquals(Set.of(2, 3, 4, 6, 7, 8), NoticeParagraphs.statutoryParagraphs(text));
        assertEquals(9, NoticeParagraphs.count(text));
        assertTrue(NoticeParagraphs.statutoryParagraphs(null).isEmpty());
    }

    // ------------------------------------------------------------------ defect-check cards in the shortlist

    private static IssueCard card(String id, List<String> stages, String... keywords) {
        return new IssueCard(id, 1, "Title " + id, "summary", List.of(), null, null, stages, List.of(keywords), List.of(),
                null, "active", null, null, null);
    }

    private static final String DRC01A = """
            FORM GST DRC-01A
            Intimation of tax ascertained as being payable under section 73(5)
            Office of the Superintendent of Central Tax
            Date: 01.06.2024
            To, M/s Noticedesk Technologies Private Limited
            1. Input tax credit availed in excess under Section 16(2)(c) and Section 16(4) for FY 2020-21.
            2. You may make submissions on or before 05.06.2024 in Part B.
            """;

    @Test
    void drc01aShortlistAlwaysContainsTheReplyTimeCard() {
        List<IssueCard> catalogue = new ArrayList<>();
        for (int i = 1; i <= 20; i++) catalogue.add(card("T-" + i, List.of("drc_01a"), "Section 16(2)(c)", "Section 16(4)"));
        catalogue.add(card("NDK-148", List.of("drc_01a"), "short time to reply"));   // no label in the text: score 0
        catalogue.add(card("X-1", List.of("order_appeal"), "Section 107"));

        // labels alone: a strict cut-off and no top-up would drop NDK-148
        CardShortlister.Options strict = new CardShortlister.Options(1.0, 0);
        assertFalse(CardShortlister.labelPicks(catalogue, CardShortlister.LabelIndex.of(catalogue),
                CardShortlister.Labels.in(DRC01A), strict).contains("NDK-148"));

        CardShortlister.Shortlist s = CardShortlister.shortlist(catalogue, "drc_01a", ResponseStage.REPLY, DRC01A, strict, null,
                CardShortlister.AI_OFF);
        assertTrue(s.cardIds().contains("NDK-148"), s.cardIds().toString());
        assertEquals(Set.of("NDK-148"), s.defectPicks(), "only catalogue cards count");
        assertEquals(1, s.details().get("defects"));
        assertTrue(s.describe().contains("defects 1"), s.describe());
        assertFalse(s.cardIds().contains("X-1"));
    }

    @Test
    void defectCardsOfAShowCauseNoticeDoNotIncludeTheDrc01aCard() {
        List<IssueCard> catalogue = List.of(card("NDK-148", List.of("drc_01a")), card("NDK-143", List.of("generic_scn")),
                card("NDK-144", List.of("order_appeal")), card("T-1", List.of("scn_73"), "Section 73"));
        String scn = "OFFICE OF THE DEPUTY COMMISSIONER OF STATE TAX\nShow Cause Notice under Section 73 of the SGST Act\n1. Demand.";
        CardShortlister.Shortlist s = CardShortlister.shortlist(catalogue, "scn_73", ResponseStage.REPLY, scn,
                new CardShortlister.Options(1.0, 0), null, CardShortlister.AI_OFF);
        assertFalse(s.defectPicks().contains("NDK-148"));
        assertEquals(Set.of("NDK-143", "NDK-144"), s.defectPicks(), "the signature check applies to every document, both stages");
        assertTrue(s.cardIds().contains("NDK-144"), "added in full whatever its stage");
    }

    @Test
    void applicableDefectCardIds() {
        List<String> drc = DefectChecker.applicableCardIds("drc_01a", "drc_01a", ResponseStage.REPLY, DRC01A);
        assertTrue(drc.containsAll(List.of("NDK-148", "NDK-141", "NDK-142", "NDK-143", "NDK-144")), drc.toString());
        List<String> stateScn = DefectChecker.applicableCardIds("scn_74", "scn_74", ResponseStage.REPLY,
                "OFFICE OF THE DEPUTY COMMISSIONER OF STATE TAX\nShow Cause Notice under Section 74(1)");
        assertTrue(stateScn.contains("NDK-104"), "premature_74 applies to a Section 74 SCN");
        assertFalse(stateScn.contains("NDK-141"), "no DIN check for a State GST document");
        assertFalse(stateScn.contains("NDK-148"));
    }
}
