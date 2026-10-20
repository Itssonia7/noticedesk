package com.noticedesk.api.service.matching;

import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.matching.CardShortlister.Labels;
import com.noticedesk.api.service.matching.CardShortlister.Shortlist;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Shortlist v2 of catalogue cards per notice: stage pool, rarity-weighted label scoring, AI picks union (synthetic cards and notices only). */
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

    // ------------------------------------------------------------------ stage pool for documents with no form name

    @Test
    void lettersWithNoFormNameArePlacedByTheLetterPatterns() {
        assertEquals(Set.of("recovery"), pool("other", "Letter regarding the outstanding dues. Action under Section 79 will follow."));
        assertEquals(Set.of("recovery"), pool("unknown", "Sub: Recovery of arrears for 2019-20"));
        assertEquals(Set.of("recovery"), pool("other", "Assessment under Section 62: you failed to file returns"));
        assertEquals(Set.of("recovery"), pool("other", "best judgment assessment order in ASMT 13"));
        assertEquals(Set.of("audit"), pool("other", "Intimation of audit under Section 65 of the CGST Act"));
        assertEquals(Set.of("inspection"), pool("other", "x".repeat(2000) + " goods detained under Section 129"),
                "the whole text is read, not only the heading");
        assertEquals(Set.of("registration", "order_appeal"), pool("letter", "cancellation of registration, REG-19 enclosed"));
        assertEquals(Set.of("refund"), pool("letter", "deficiency memo; see RFD-08 issued earlier"));
        // the earliest pattern wins
        assertEquals(Set.of("audit"), pool("other", "Audit under Section 65; recovery will be made under Section 79"));
        // nothing to read: no stage filter
        assertNull(pool("other", "Please appear for a personal hearing on 12.03.2024."));
        // a document the type or heading already places is not re-read
        assertEquals(Set.of("scn_73", "generic_scn"), pool("scn_73", "Show cause notice under Section 73; recovery under Section 79"));
    }

    /** The stages of the pool's cards for a catalogue of one card per stage; null when the pool is every card. */
    private static Set<String> pool(String type, String text) {
        List<String> stages = List.of("scn_73", "scn_74", "generic_scn", "drc_01a", "asmt_10", "order_appeal", "registration",
                "refund", "recovery", "inspection", "audit");
        List<IssueCard> cards = new ArrayList<>();
        for (String st : stages) cards.add(card("S-" + st, List.of(st), "x"));
        CardShortlister.StagePool p = CardShortlister.stagePool(cards, type, StageResolver.resolve(type, text), text);
        if (p.noticeKeys().isEmpty()) {
            assertEquals(cards.size(), p.size());
            return null;
        }
        Set<String> out = new java.util.TreeSet<>();
        for (IssueCard c : p.cards()) out.addAll(c.stages());
        return out;
    }

    @Test
    void otherStageCardsAlwaysPassThePool() {
        List<IssueCard> cards = List.of(card("T-1", List.of("recovery"), "x"), card("T-2", List.of("other"), "x"),
                card("T-3", List.of("scn_73"), "x"));
        assertEquals(List.of("T-1", "T-2"), ids(CardShortlister.stagePool(cards, "other", null, "Section 79 recovery").cards()));
    }

    // ------------------------------------------------------------------ label scoring

    private static final String NOTICE = """
            Show Cause Notice under Section 73 of the CGST Act, 2017
            1. ITC of Rs. 1,00,000 is not reflected in GSTR-2B as required by Section 16(2)(aa) read with Rule 36(4).
            """;

    /**
     * Ten scn_73 cards: Section 73 on five (weight ln 2), Section 16(2)(aa) on two (ln 5), Rule 36(4) on one (ln 10).
     * Scores against {@link #NOTICE}: T-01 = ln5 + ln10 = 3.91, T-02 = ln5 + ln2 = 2.30, T-03..T-06 = ln2 = 0.69,
     * T-07..T-10 = 0.
     */
    private static List<IssueCard> scored() {
        return List.of(
                card("T-01", List.of("scn_73"), "Section 16(2)(aa)", "Rule 36(4)"),
                card("T-02", List.of("scn_73"), "Section 16(2)(aa)", "Section 73", "Sec. 73 of the CGST Act"),
                card("T-03", List.of("scn_73"), "Section 73"),
                card("T-04", List.of("scn_73"), "Section 73"),
                card("T-05", List.of("scn_73"), "Section 73"),
                card("T-06", List.of("scn_73"), "Section 73"),
                card("T-07", List.of("scn_73"), "Section 74"),
                card("T-08", List.of("scn_73"), "Section 54"),
                card("T-09", List.of("scn_73"), "routine mismatch"),
                card("T-10", List.of("scn_73"), "Section 129"));
    }

    @Test
    void rareKeywordsWeighMoreAndDistinctKeywordsCountOnce() {
        Map<String, Double> sc = CardShortlister.scores(scored(), scored(), NOTICE);
        assertEquals(Math.log(5) + Math.log(10), sc.get("T-01"), 1e-9);
        assertEquals(Math.log(5) + Math.log(2), sc.get("T-02"), 1e-9, "'Section 73' and 'Sec. 73 of the CGST Act' are one keyword");
        assertEquals(Math.log(2), sc.get("T-03"), 1e-9);
        assertEquals(0.0, sc.get("T-07"), 1e-9, "Section 74 is not cited");
        assertEquals(0.0, sc.get("T-09"), 1e-9, "free-text keywords do not score");
    }

    @Test
    void keywordOnEveryCardWeighsNothing() {
        List<IssueCard> cards = List.of(card("T-1", List.of("scn_73"), "Section 73"), card("T-2", List.of("scn_73"), "Section 73"));
        assertEquals(Map.of("T-1", 0.0, "T-2", 0.0), CardShortlister.scores(cards, cards, NOTICE));
    }

    @Test
    void relativeCutoffKeepsCardsNearTheBestScore() {
        // cutoff 0.35 x 3.91 = 1.37: T-01 and T-02 only (min-cards off)
        Shortlist s = shortlist(scored(), new CardShortlister.Options(0.35, 0), null, CardShortlister.AI_OFF);
        assertEquals(Set.of("T-01", "T-02"), s.labelPicks());
        // a cutoff of 0.1 (0.39) also keeps the Section 73 cards
        Shortlist low = shortlist(scored(), new CardShortlister.Options(0.1, 0), null, CardShortlister.AI_OFF);
        assertEquals(Set.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"), low.labelPicks());
    }

    @Test
    void minCardsTopsUpByScorePositiveScoresOnly() {
        // the cutoff keeps two; min-cards 3 adds the best of the rest (ties: catalogue order)
        assertEquals(List.of("T-01", "T-02", "T-03"),
                List.copyOf(shortlist(scored(), new CardShortlister.Options(0.35, 3), null, CardShortlister.AI_OFF).labelPicks()));
        // the default (15) keeps every card with a positive score, never a zero-score card
        Shortlist dflt = CardShortlister.shortlist(scored(), "scn_73", ResponseStage.REPLY, NOTICE);
        assertEquals(List.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"), dflt.cardIds());
        assertEquals(CardShortlister.LEVEL_LABELS, dflt.level());
        assertEquals(CardShortlister.AI_OFF, dflt.aiStatus());
    }

    @Test
    void thresholdsComeFromTheProperties() {
        com.noticedesk.api.config.AppProperties.Matching.Shortlist p = new com.noticedesk.api.config.AppProperties.Matching.Shortlist();
        assertEquals(CardShortlister.DEFAULT_RELATIVE_CUTOFF, p.getRelativeCutoff());
        assertEquals(CardShortlister.DEFAULT_MIN_CARDS, p.getMinCards());
        assertTrue(p.isAiTitles());
        assertEquals(CardShortlister.DEFAULT_RELATIVE_CUTOFF_WITH_AI, p.getRelativeCutoffWithAi());
        assertEquals(CardShortlister.DEFAULT_MIN_CARDS_WITH_AI, p.getMinCardsWithAi());
        assertEquals(0.5, CardShortlister.DEFAULT_RELATIVE_CUTOFF_WITH_AI);
        assertEquals(8, CardShortlister.DEFAULT_MIN_CARDS_WITH_AI);
        assertEquals(CardShortlister.Options.DEFAULT, new CardShortlister.Options(Double.NaN, -1, 2.0, -3), "bad values fall back to the defaults");
        assertEquals(new CardShortlister.Options(0.2, 4, 0.2, 4), new CardShortlister.Options(0.2, 4), "two values: the same with or without AI");
    }

    @Test
    void stricterCutoffWhenTheAiPassNamedACard() {
        // labels only, 0.1 x 3.91 = 0.39: T-01..T-06; with AI picks, 0.6 x 3.91 = 2.35 keeps T-01, min 2 adds T-02
        CardShortlister.Options opt = new CardShortlister.Options(0.1, 0, 0.6, 2);
        assertEquals(Set.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"),
                shortlist(scored(), opt, null, CardShortlister.AI_OFF).labelPicks());
        Shortlist withAi = shortlist(scored(), opt, Set.of("T-09"), CardShortlister.AI_USED);
        assertEquals(Set.of("T-01", "T-02"), withAi.labelPicks());
        assertEquals(List.of("T-01", "T-02", "T-09"), withAi.cardIds());
        assertEquals(Set.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"),
                shortlist(scored(), opt, null, CardShortlister.AI_SKIPPED).labelPicks(), "a failed pass: the labels-only thresholds");
        assertEquals(Set.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"),
                shortlist(scored(), opt, Set.of(), CardShortlister.AI_USED).labelPicks(), "an empty answer: the labels-only thresholds");
        assertEquals(Set.of("T-01", "T-02", "T-03", "T-04", "T-05", "T-06"),
                shortlist(scored(), opt, Set.of("NOPE"), CardShortlister.AI_USED).labelPicks(), "only ids outside the catalogue: no AI picks");
    }

    @Test
    void parseFallbackMarkIsKeptAndShownInTheDetails() {
        Shortlist s = shortlist(scored(), new CardShortlister.Options(0.35, 0), Set.of("T-09"), CardShortlister.AI_USED);
        assertFalse(s.aiParseFallback());
        assertFalse(s.details().containsKey("ai_parse_fallback"));
        Shortlist marked = s.withAiParseFallback(true);
        assertTrue(marked.aiParseFallback());
        assertEquals(true, marked.details().get("ai_parse_fallback"));
        assertEquals(s.cardIds(), marked.cardIds());
    }

    private static Shortlist shortlist(List<IssueCard> cards, CardShortlister.Options opt, Set<String> ai, String aiStatus) {
        return CardShortlister.shortlist(cards, "scn_73", ResponseStage.REPLY, NOTICE, opt, ai, aiStatus);
    }

    // ------------------------------------------------------------------ union with the AI picks; titles only

    /**
     * Label picks T-01 / T-02 (min-cards off); T-01 names T-07 and T-02 names T-20 (label-only siblings: titles
     * only); the AI picks T-09 (no label), which names T-10 (in the pool: full) and T-30 (appeal stage: titles only),
     * and T-31 (appeal stage, outside the pool: still full, the title pass read it by meaning).
     */
    private static List<IssueCard> unionCatalogue() {
        List<IssueCard> cards = new ArrayList<>(scored());
        cards.set(0, card("T-01", List.of("scn_73"), List.of("Section 16(2)(aa)", "Rule 36(4)"), List.of("Not this if X (use T-07)")));
        cards.set(1, card("T-02", List.of("scn_73"), List.of("Section 16(2)(aa)", "Section 73"), List.of("use T-20")));
        cards.set(8, card("T-09", List.of("scn_73"), List.of("vague grounds"), List.of("use T-10", "use T-30")));
        cards.add(card("T-20", List.of("scn_73"), "Section 61"));
        cards.add(card("T-30", List.of("order_appeal"), "Section 107"));
        cards.add(card("T-31", List.of("order_appeal"), "Section 107(6)"));
        return cards;
    }

    @Test
    void unionOfLabelsAiAndTheAiPicksSiblingsInThePool() {
        Shortlist s = shortlist(unionCatalogue(), new CardShortlister.Options(0.35, 0), new LinkedHashSet<>(List.of("T-09", "T-31", "NOPE")),
                CardShortlister.AI_USED);
        assertEquals(CardShortlister.LEVEL_HYBRID, s.level());
        assertEquals(List.of("T-01", "T-02", "T-09", "T-10", "T-31"), s.cardIds(), "catalogue order");
        assertEquals(Set.of("T-01", "T-02"), s.labelPicks());
        assertEquals(Set.of("T-09", "T-31"), s.aiPicks(), "ids not in the catalogue are dropped");
        assertEquals(1, s.siblingCount(), "T-10");
        assertEquals(List.of("T-07", "T-20", "T-30"), s.titlesOnlyIds(),
                "siblings of label-only picks, and an AI pick's sibling outside the pool, are listed by title only");
        assertEquals(11, s.poolSize(), "T-01..T-10 and T-20");
        assertEquals("5 cards (pool 11; labels 2, ai 2, siblings 1; titles-only 3)", s.describe());
        assertEquals(Map.of("size", 5, "level", "hybrid", "catalogue_size", 13, "pool", 11, "labels", 2, "ai", 2,
                "ai_status", "used", "siblings", 1, "titles_only", 3), s.details());
        assertEquals(s.describe(), Shortlist.describe(s.details()));
    }

    @Test
    void aiSkippedLeavesTheLabelsAlone() {
        Shortlist s = shortlist(unionCatalogue(), new CardShortlister.Options(0.35, 3), null, CardShortlister.AI_SKIPPED);
        assertEquals(CardShortlister.LEVEL_LABELS, s.level());
        assertTrue(s.aiSkipped());
        assertEquals(List.of("T-01", "T-02", "T-03"), s.cardIds());
        assertEquals(List.of("T-07", "T-20"), s.titlesOnlyIds(), "no AI: the label picks' siblings by title only");
        assertEquals("3 cards (pool 11; labels 3, ai skipped, siblings 0; titles-only 2)", s.describe());
    }

    @Test
    void anEmptyAiAnswerIsStillHybrid() {
        Shortlist s = shortlist(scored(), new CardShortlister.Options(0.35, 3), Set.of(), CardShortlister.AI_USED);
        assertEquals(CardShortlister.LEVEL_HYBRID, s.level());
        assertEquals(List.of("T-01", "T-02", "T-03"), s.cardIds());
        assertEquals("3 cards (pool 10; labels 3, ai 0, siblings 0; titles-only 0)", s.describe());
    }

    // ------------------------------------------------------------------ floor, widening, determinism

    @Test
    void floorUsesTheStagePoolThenTheFullCatalogue() {
        List<IssueCard> cards = new ArrayList<>(List.of(
                card("T-001", List.of("scn_73"), List.of("Section 16(2)(aa)"), List.of("use T-002")),
                card("T-002", List.of("scn_74"), "x"),
                card("T-003", List.of("scn_73"), "Section 16(4)"),
                card("T-004", List.of("scn_73"), "y"),
                card("T-005", List.of("order_appeal"), "z")));
        // one label pick (< 3): the stage pool
        Shortlist stage = CardShortlister.shortlist(cards, "scn_73", null, NOTICE);
        assertEquals(CardShortlister.LEVEL_STAGE, stage.level());
        assertEquals(List.of("T-001", "T-003", "T-004"), stage.cardIds());
        assertEquals(List.of("T-002"), stage.titlesOnlyIds(), "T-001's sibling outside the pool, by title");
        assertTrue(stage.describe().startsWith("3 cards (level stage; pool 3;"), stage.describe());

        // no stage filter (unknown notice) and no label hit: the pool is the catalogue -> full
        Shortlist unknown = CardShortlister.shortlist(cards, "unknown", null, "a letter with no references");
        assertEquals(CardShortlister.LEVEL_FULL, unknown.level());
        assertEquals(5, unknown.size());
        assertTrue(unknown.isFull());
    }

    @Test
    void wideningRendersTheStagePoolNeverMoreThanThePool() {
        Shortlist w = CardShortlister.widened(unionCatalogue(), "scn_73", ResponseStage.REPLY, NOTICE);
        assertEquals(CardShortlister.LEVEL_STAGE, w.level());
        assertEquals(11, w.size());
        assertFalse(w.cardIds().contains("T-30"));
        // the pool is the whole catalogue: full
        assertTrue(CardShortlister.widened(unionCatalogue(), "unknown", null, "no references").isFull());
    }

    @Test
    void fullShortlistOfTheCatalogue() {
        Shortlist s = CardShortlister.full(scored());
        assertTrue(s.isFull());
        assertEquals(10, s.size());
        assertEquals(10, s.details().get("size"));
        assertEquals("full", s.details().get("level"));
    }

    @Test
    void deterministic() {
        Set<String> ai = new LinkedHashSet<>(List.of("T-09"));
        Shortlist a = shortlist(unionCatalogue(), CardShortlister.Options.DEFAULT, ai, CardShortlister.AI_USED);
        for (int i = 0; i < 5; i++) {
            Shortlist b = shortlist(unionCatalogue(), CardShortlister.Options.DEFAULT, ai, CardShortlister.AI_USED);
            assertEquals(a.cardIds(), b.cardIds());
            assertEquals(a.titlesOnlyIds(), b.titlesOnlyIds());
            assertEquals(a.details(), b.details());
        }
    }

    // ------------------------------------------------------------------ rendering for the matcher

    @Test
    void shortlistRenderDropsKeywordsAndEndsWithTheTitlesOnlyBlock() {
        CatalogueService catalogue = new CatalogueService(null, null, null);
        Shortlist s = shortlist(unionCatalogue(), new CardShortlister.Options(0.35, 0), Set.of("T-09"), CardShortlister.AI_USED);
        String text = catalogue.renderShortlistText(s.cards(), s.titlesOnly());
        assertTrue(text.contains("Total Cards: " + s.size()), text);
        assertTrue(text.contains("CARD_ID: T-01 (v1)"));
        assertFalse(text.contains("KEYWORDS:"), "keywords did their work in code");
        assertTrue(text.contains("NOT THIS IF:"));
        assertTrue(text.endsWith(CatalogueService.TITLES_ONLY_HEADING + "\nT-07 | Title T-07\nT-20 | Title T-20\nT-30 | Title T-30\n"), text);
        // the full-catalogue render keeps the KEYWORDS line (the catalogue hash is taken of it)
        String full = catalogue.renderCatalogueCompactText(unionCatalogue());
        assertTrue(full.contains("KEYWORDS: Section 16(2)(aa), Rule 36(4)"));
        assertFalse(full.contains(CatalogueService.TITLES_ONLY_HEADING));
        assertFalse(catalogue.renderShortlistText(s.cards(), List.of()).contains(CatalogueService.TITLES_ONLY_HEADING));
    }

    private static List<String> ids(List<IssueCard> cards) {
        return cards.stream().map(IssueCard::cardId).toList();
    }
}
