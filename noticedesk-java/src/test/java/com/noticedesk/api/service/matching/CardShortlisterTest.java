package com.noticedesk.api.service.matching;

import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.CardShortlister.Labels;
import com.noticedesk.api.service.matching.CardShortlister.Shortlist;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Code shortlist of catalogue cards per notice (synthetic cards and notices only). */
class CardShortlisterTest {

    private static IssueCard card(String id, List<String> stages, List<String> keywords, List<String> notThisIf) {
        return new IssueCard(id, 1, "Title " + id, "summary", List.of(), null, null, stages, keywords, notThisIf,
                null, "active", null, null, null);
    }

    private static IssueCard card(String id, List<String> stages, String... keywords) {
        return card(id, stages, List.of(keywords), List.of());
    }

    // ------------------------------------------------------------------ keyword normalisation

    private static boolean hits(String keyword, String text) {
        Labels kw = CardShortlister.labelKeyword(keyword);
        assertNotNull(kw, "'" + keyword + "' should be a label keyword");
        return Labels.in(text).covers(kw);
    }

    @Test
    void sectionSpellingsMatch() {
        assertTrue(hits("Section 16(2)(c)", "ITC denied under Sec. 16(2)(c) of the CGST Act"));
        assertTrue(hits("Section 16(2)(c)", "as required by section 16 (2)(c) read with Rule 36"));
        assertTrue(hits("Section 16(2)(c)", "u/s 16(2)(c)"));
        assertTrue(hits("Sec. 16(2)(c)", "SECTION 16 ( 2 ) ( c )"));
        assertTrue(hits("section 74A", "proceedings under Section 74A"));
        assertFalse(hits("section 74", "proceedings under Section 74A"), "74 and 74A are different sections");
        assertFalse(hits("section 73", "an amount of Rs. 73,000 was demanded"));
    }

    @Test
    void ruleSpellingsMatch() {
        assertTrue(hits("Rule 36(4)", "in terms of Rule 36 (4) of the CGST Rules, 2017"));
        assertTrue(hits("rule 36(4)", "Rule 36(4)"));
        assertFalse(hits("Rule 36(4)", "Section 36(4) of the Act"), "a rule keyword is not hit by a section");
        assertFalse(hits("Rule 86A", "Rule 86B restriction"));
    }

    @Test
    void subSectionExactnessAtTheKeywordsLevel() {
        assertTrue(hits("Section 16", "Section 16(4) time limit"), "a section keyword hits any of its sub-sections");
        assertTrue(hits("Section 16(2)", "Section 16(2)(c)"));
        assertFalse(hits("Section 16(4)", "ITC denied under Section 16(2)(c)"));
        assertFalse(hits("Section 16(4)", "Section 16 of the Act"), "the notice must state the keyword's sub-section");
    }

    @Test
    void sectionListsAndTheStatutoryReferenceLine() {
        String text = "Statutory reference: Sections 16(2)(c), 73 and 74A of the CGST Act";
        assertTrue(hits("Section 16(2)(c)", text));
        assertTrue(hits("Section 73", text));
        assertTrue(hits("Section 74A", text));
        assertFalse(hits("Section 74", text));
    }

    @Test
    void formNamesMatch() {
        assertTrue(hits("DRC-01A", "intimation in FORM GST DRC 01A dated 01.02.2024"));
        assertTrue(hits("DRC 01A", "DRC-01A"));
        assertTrue(hits("DRC-01A", "DRC01A"));
        assertFalse(hits("DRC-01A", "FORM GST DRC-01 is issued"), "DRC-01 is not DRC-01A");
        assertTrue(hits("ASMT-13", "best judgment order in ASMT 13"));
        assertTrue(hits("GSTR-2A", "mismatch with GSTR 2A"));
        assertFalse(hits("GSTR-2A", "mismatch with GSTR-2B"));
        assertTrue(hits("RFD-06", "order in Form GST RFD-06"));
        assertTrue(hits("REG-17", "show cause notice in REG 17"));
        assertTrue(hits("e-way bill", "goods moved without an E-Way Bill"));
        assertTrue(hits("e-way bill", "the eway bill had expired"));
    }

    @Test
    void notificationsAndCirculars() {
        assertTrue(hits("Notification 13/2017", "Notification No. 13/2017-Central Tax (Rate)"));
        assertFalse(hits("Notification 13/2017", "Notification No. 13/2018-Central Tax"));
        assertTrue(hits("Circular 183", "as clarified in Circular No. 183/15/2022-GST"));
        assertFalse(hits("Circular 183", "Circular No. 18/2019"));
    }

    @Test
    void freeTextKeywordsAreNotLabels() {
        assertNull(CardShortlister.labelKeyword("routine mismatch"));
        assertNull(CardShortlister.labelKeyword("gstr-3b vs 2a"));
        assertNull(CardShortlister.labelKeyword("section 50 interest on net liability"));
        assertNull(CardShortlister.labelKeyword("30th november"));
        assertNotNull(CardShortlister.labelKeyword("Section 16(4) of the CGST Act"));
        assertNotNull(CardShortlister.labelKeyword("Section 16(2)(aa) read with Rule 36(4)"));
    }

    // ------------------------------------------------------------------ stage table

    @Test
    void stageTable() {
        assertEquals(Set.of("scn_73", "generic_scn"), allowed("scn_73", null, ""));
        assertEquals(Set.of("scn_74", "generic_scn"), allowed("scn_74", null, ""));
        assertEquals(Set.of("drc_01a", "generic_scn"), allowed("drc_01a", null, ""));
        assertEquals(Set.of("asmt_10", "generic_scn"), allowed("asmt_10", null, ""));
        assertEquals(Set.of("order_appeal"), allowed("order", ResponseStage.APPEAL, "ORDER in FORM GST DRC-07"));
        assertEquals(Set.of("order_appeal", "refund"), allowed("order", ResponseStage.APPEAL, "Refund rejection order RFD-06"));
        assertEquals(Set.of("order_appeal", "registration"), allowed("order", ResponseStage.APPEAL, "Order of cancellation REG-05"));
        assertEquals(Set.of("order_appeal", "inspection"), allowed("order", ResponseStage.APPEAL, "Order of detention MOV-09"));
        assertEquals(Set.of("registration", "order_appeal"), allowed("registration", ResponseStage.REVOCATION, "Order REG-19"));
        assertEquals(Set.of("registration"), allowed("registration", ResponseStage.REPLY, "Show cause notice REG-17"));
        assertEquals(Set.of("refund"), allowed("refund", null, "Notice in RFD-08"));
        assertEquals(Set.of("recovery"), allowed("recovery", null, "Notice in DRC-13 to the bank"));
        assertEquals(Set.of("recovery"), allowed("other", null, "Attachment order DRC-22"), "the heading form decides");
        assertEquals(Set.of("inspection"), allowed("inspection", null, "Notice under Section 129 in MOV-07"));
        assertEquals(Set.of("audit"), allowed("audit", null, "Notice of audit ADT-01"));
        assertNull(allowed("other", null, "a letter"), "other: no stage filter");
        assertNull(allowed("unknown", null, ""), "unknown: no stage filter");
        assertNull(allowed("anti_profiteering", null, ""), "a type not in the table switches the filter off");
    }

    private static Set<String> allowed(String type, ResponseStage stage, String text) {
        return CardShortlister.allowedStages(CardShortlister.noticeKeys(type, stage, text));
    }

    @Test
    void cardStageOtherAndEmptyPassEveryNotice() {
        Set<String> scn73 = Set.of("scn_73", "generic_scn");
        assertTrue(CardShortlister.stageFits(card("T-1", List.of("other")), scn73));
        assertTrue(CardShortlister.stageFits(card("T-2", List.of()), scn73));
        assertTrue(CardShortlister.stageFits(card("T-3", List.of("SCN_73")), scn73));
        assertFalse(CardShortlister.stageFits(card("T-4", List.of("order_appeal")), scn73));
        assertTrue(CardShortlister.stageFits(card("T-4", List.of("order_appeal")), null));
    }

    // ------------------------------------------------------------------ the four steps

    private static final String SCN_73 = """
            Show Cause Notice under Section 73 of the CGST Act, 2017
            1. ITC of Rs. 1,00,000 availed in GSTR-3B is not reflected in GSTR-2B as required by Section 16(2)(aa).
            Statutory reference: Sections 16(2)(aa), 73
            """;

    private static List<IssueCard> catalogue() {
        return List.of(
                card("T-001", List.of("scn_73"), List.of("Section 16(2)(aa)", "gstr-2b mismatch"), List.of("Not this if a Section 74 SCN (use T-002)")),
                card("T-002", List.of("scn_74"), "Section 16(2)(aa)"),
                card("T-003", List.of("scn_73", "scn_74"), "Section 16(4)"),
                card("T-004", List.of("generic_scn"), "Section 73"),
                card("T-005", List.of("order_appeal"), "Section 16(2)(aa)"),
                card("T-006", List.of("other"), "GSTR-2B"),
                card("T-007", List.of("scn_73"), "routine mismatch"),
                card("T-008", List.of("refund"), "RFD-06"));
    }

    @Test
    void stageThenKeywordThenSiblings() {
        Shortlist s = CardShortlister.shortlist(catalogue(), "scn_73", ResponseStage.REPLY, SCN_73);
        assertEquals(CardShortlister.LEVEL_KEYWORD, s.level());
        assertEquals(8, s.catalogueSize());
        assertEquals(5, s.stageCount(), "T-001, T-003, T-004, T-006, T-007");
        assertEquals(3, s.keywordCount(), "T-001 (16(2)(aa)), T-004 (73), T-006 (GSTR-2B)");
        assertEquals(1, s.siblingCount(), "T-002 named by T-001's NOT THIS IF");
        assertEquals(List.of("T-001", "T-002", "T-004", "T-006"), s.cardIds(), "catalogue order");
        assertFalse(s.cardIds().contains("T-003"), "Section 16(4) is not cited");
        assertFalse(s.cardIds().contains("T-005"), "appeal-stage card");
        assertEquals("4 cards (level keyword; stage 5, keyword 3, siblings 1)", s.describe());
    }

    @Test
    void siblingsAreOneLevelOnly() {
        List<IssueCard> cards = List.of(
                card("T-101", List.of("scn_73"), List.of("Section 50"), List.of("use T-102")),
                card("T-102", List.of("scn_73"), List.of("free text"), List.of("use T-103")),
                card("T-103", List.of("scn_73"), "free text"),
                card("T-104", List.of("scn_73"), "Section 50(3)"),
                card("T-105", List.of("scn_73"), "Section 50(1)"));
        Shortlist s = CardShortlister.shortlist(cards, "scn_73", null, "Interest under Section 50(3) and Section 50(1)");
        assertEquals(List.of("T-101", "T-102", "T-104", "T-105"), s.cardIds());
        assertEquals(1, s.siblingCount());
    }

    @Test
    void floorDropsTheKeywordFilterThenUsesTheFullCatalogue() {
        // keyword step keeps one card (T-001 + sibling T-002 = 2 < 3): stage level
        List<IssueCard> cards = new ArrayList<>(List.of(
                card("T-001", List.of("scn_73"), List.of("Section 16(2)(aa)"), List.of("use T-002")),
                card("T-002", List.of("scn_74"), "x"),
                card("T-003", List.of("scn_73"), "Section 16(4)"),
                card("T-004", List.of("scn_73"), "y"),
                card("T-005", List.of("order_appeal"), "z")));
        Shortlist stage = CardShortlister.shortlist(cards, "scn_73", null, SCN_73);
        assertEquals(CardShortlister.LEVEL_STAGE, stage.level());
        assertEquals(List.of("T-001", "T-003", "T-004"), stage.cardIds());

        // stage step keeps two: full catalogue
        Shortlist full = CardShortlister.shortlist(cards.subList(0, 4).stream()
                .map(c -> c.cardId().equals("T-004") ? card("T-004", List.of("refund"), "y") : c).toList(), "scn_73", null, SCN_73);
        assertEquals(CardShortlister.LEVEL_FULL, full.level());
        assertEquals(4, full.size());
        assertTrue(full.isFull());

        // no stage filter (unknown notice) and no keyword hit: the stage level would be the whole catalogue -> full
        Shortlist unknown = CardShortlister.shortlist(cards, "unknown", null, "a letter with no references");
        assertEquals(CardShortlister.LEVEL_FULL, unknown.level());
        assertEquals(5, unknown.size());
    }

    @Test
    void fullShortlistOfTheCatalogue() {
        Shortlist s = CardShortlister.full(catalogue());
        assertTrue(s.isFull());
        assertEquals(8, s.size());
        assertEquals(8, s.details().get("size"));
        assertEquals("full", s.details().get("level"));
    }

    @Test
    void deterministic() {
        Shortlist a = CardShortlister.shortlist(catalogue(), "scn_73", ResponseStage.REPLY, SCN_73);
        for (int i = 0; i < 5; i++) {
            Shortlist b = CardShortlister.shortlist(catalogue(), "scn_73", ResponseStage.REPLY, SCN_73);
            assertEquals(a.cardIds(), b.cardIds());
            assertEquals(a.details(), b.details());
        }
    }
}
