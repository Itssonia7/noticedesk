package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TemplateFillServiceTest {

    private TemplateFillService fillerService;

    @BeforeEach
    void setUp() {
        fillerService = new TemplateFillService(null, null, new ObjectMapper());
    }

    private DraftingInput createTestDraftingInput(String legalName, String gstin) {
        return new DraftingInput(
                UUID.randomUUID(), "formal", "Instructions", legalName, "PAN123", "Company", "Regular", gstin, "Maharashtra", "CGST Act", "2022-23", "2023-24",
                Map.of("issue_date", "2023-10-15"), Map.of(), "OCR", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );
    }

    @Test
    void testFormatIndianCurrency() {
        assertEquals("Rs 4,82,310", TemplateFillService.formatIndianCurrency(new BigDecimal("482310")));
        assertEquals("Rs 1,80,000", TemplateFillService.formatIndianCurrency(new BigDecimal("180000")));
        assertEquals("Rs 1,00,00,000", TemplateFillService.formatIndianCurrency(new BigDecimal("10000000")));
    }

    @Test
    void testPlaceholderSubstitutionAndIndianFormatting() {
        ReplyTemplate template = new ReplyTemplate(
                "TPL-006", 1, "CARD-006",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-1", 4, "<p>Discrepancy of {{issue.amount_tax}} for {{client.legal_name}} on {{notice.issue_date}}.</p>", List.of("CIT-1"), null)
                ),
                List.of("issue.amount_tax", "client.legal_name", "notice.issue_date"),
                List.of(), "Summary for {{client.legal_name}}", List.of(), null, null, null
        );
        fillerService.registerInMemoryTemplate(template);

        MatchedIssue issue = new MatchedIssue(
                1, "full", List.of("CARD-006"), "Why", List.of(),
                Map.of("period", "2022-23", "amount", 482310.0), List.of(), List.of()
        );
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN-123", "DIN-123", "2023-10-15", "30 Days", "2022-23", "2022-23", 482310.0, "State Tax Officer");
        DraftingInput input = createTestDraftingInput("M/s ABC Corp", "27AAACB1234A1Z1");

        FilledTemplateResult result = fillerService.fillTemplateForIssue(issue, noticeInfo, input);

        assertNotNull(result);
        assertFalse(result.periodMissing());
        assertEquals("TPL-006", result.templateId());
        assertEquals(1, result.blocks().size());
        assertTrue(result.blocks().get(0).filledHtml().contains("Rs 4,82,310"));
        assertTrue(result.blocks().get(0).filledHtml().contains("M/s ABC Corp"));
        assertTrue(result.blocks().get(0).filledHtml().contains("15-10-2023"));
    }

    @Test
    void testMissingVariableGeneratesMissingMarkerAndFlag() {
        ReplyTemplate template = new ReplyTemplate(
                "TPL-006", 1, "CARD-006",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-1", 4, "<p>Amount {{issue.amount_tax}} and Trade {{client.trade_name}}.</p>", List.of(), null)
                ),
                List.of("issue.amount_tax", "client.trade_name"),
                List.of(), "Summary", List.of(), null, null, null
        );
        fillerService.registerInMemoryTemplate(template);

        MatchedIssue issue = new MatchedIssue(
                1, "full", List.of("CARD-006"), "Why", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0), List.of(), List.of()
        );
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo(null, null, null, null, null, null, null, null);
        DraftingInput input = createTestDraftingInput("M/s ABC", "GSTIN123");

        FilledTemplateResult result = fillerService.fillTemplateForIssue(issue, noticeInfo, input);

        assertTrue(result.blocks().get(0).filledHtml().contains("[[MISSING: client.trade_name]]"));
        assertTrue(result.flags().contains("missing_client.trade_name"));
    }

    @Test
    void testUnknownPlaceholderThrowsException() {
        ReplyTemplate template = new ReplyTemplate(
                "TPL-BUG", 1, "CARD-006",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-1", 4, "<p>Undeclared {{unknown.var}} placeholder.</p>", List.of(), null)
                ),
                List.of("issue.amount_tax"), // "unknown.var" not listed in variables!
                List.of(), "Summary", List.of(), null, null, null
        );
        fillerService.registerInMemoryTemplate(template);

        MatchedIssue issue = new MatchedIssue(
                1, "full", List.of("CARD-006"), "Why", List.of(),
                Map.of("period", "2022-23", "amount", 100000.0), List.of(), List.of()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                fillerService.fillTemplateForIssue(issue, null, null)
        );
        assertTrue(ex.getMessage().contains("Template Bug: Unknown placeholder 'unknown.var'"));
    }

    @Test
    void testMissingPeriodGeneratesMissingPeriodMarkerAndFlag() {
        MatchedIssue issueWithoutPeriod = new MatchedIssue(
                1, "full", List.of("CARD-006"), "Why", List.of(),
                Map.of("amount", 100000.0), // No "period" key!
                List.of(), List.of()
        );

        FilledTemplateResult result = fillerService.fillTemplateForIssue(issueWithoutPeriod, null, null);

        assertTrue(result.periodMissing());
        assertTrue(result.summaryLine().contains("[[MISSING: period]]"));
        assertTrue(result.flags().contains("missing_period"));
    }

    // ------------------------------------------------------------------ template version selection by period overlap

    private static ReplyTemplate windowed(String cardId, int version, LocalDate from, LocalDate to) {
        return new ReplyTemplate("TPL-" + cardId + "-v" + version, version, cardId, from, to, "active",
                List.of(new TemplateBlock("BLK-" + cardId + "-" + version, 4, "<p>Version " + version + " ground.</p>", List.of(), null)),
                List.of(), List.of(), "Version " + version + " summary.", List.of(), null, null, null);
    }

    private static MatchedIssue fullIssue(String cardId, String period) {
        return new MatchedIssue(1, "full", List.of(cardId), "w", List.of(), Map.of("period", period), List.of(), List.of());
    }

    @Test
    void fy2017_18_isReadFromGstStart_andGetsTheTemplateEffectiveFrom1July2017() {
        // live finding: "FY 2017-18" starts 2017-04-01, before the 2017-07-01 effective_from of every card; the
        // old in-force-on-start rule found no version and the issue went to the AI writers
        fillerService.registerInMemoryTemplate(windowed("NDK-037", 1, LocalDate.of(2017, 7, 1), null));

        TemplateFillService.TemplateSelection sel = fillerService.selectTemplate("NDK-037", "FY 2017-18");
        assertNotNull(sel.template());
        assertEquals(LocalDate.of(2017, 7, 1), sel.period().start(), "start clamped to the GST start date");
        assertEquals(LocalDate.of(2018, 3, 31), sel.period().end());
        assertFalse(sel.crossesVersionBoundary());
        assertTrue(sel.flags().isEmpty(), sel.flags().toString());

        FilledTemplateResult r = fillerService.fillTemplateForIssue(fullIssue("NDK-037", "FY 2017-18"), null, null);
        assertEquals("TPL-NDK-037-v1", r.templateId());
        assertTrue(r.hasTemplate());
        assertFalse(r.flags().contains("no_template_available"));
        assertFalse(r.flags().contains(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH));
        assertFalse(r.flags().contains(TemplateFillService.PERIOD_CROSSES_TEMPLATE_VERSION_FLAG));
    }

    @Test
    void legalDateWindowOverlappingThePeriod_isChosen() {
        // a COVID-limitation card whose window is a legal regime (2020-03-01 to 2022-02-28), not a tax period
        fillerService.registerInMemoryTemplate(windowed("NDK-COVID", 1, LocalDate.of(2020, 3, 1), LocalDate.of(2022, 2, 28)));

        TemplateFillService.TemplateSelection sel = fillerService.selectTemplate("NDK-COVID", "July 2019 to March 2020");
        assertNotNull(sel.template());
        assertEquals(1, sel.template().version());
        assertTrue(sel.flags().isEmpty(), "an overlapping window is a normal match: " + sel.flags());
        // the period starts before the window: the existing boundary warning still applies
        assertTrue(sel.crossesVersionBoundary());

        FilledTemplateResult r = fillerService.fillTemplateForIssue(fullIssue("NDK-COVID", "July 2019 to March 2020"), null, null);
        assertTrue(r.hasTemplate());
        assertTrue(r.flags().contains(TemplateFillService.PERIOD_CROSSES_TEMPLATE_VERSION_FLAG));
        assertFalse(r.flags().contains(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH));
    }

    @Test
    void noOverlappingVersion_usesTheNearestWindowWithAWarnFlagAndDetails() {
        fillerService.registerInMemoryTemplate(windowed("NDK-NEAR", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2018, 3, 31)));
        fillerService.registerInMemoryTemplate(windowed("NDK-NEAR", 2, LocalDate.of(2021, 1, 1), LocalDate.of(2022, 2, 28)));

        // FY 2019-20 (2019-04-01..2020-03-31): 366 days after v1 ends, 276 days before v2 starts -> v2
        TemplateFillService.TemplateSelection sel = fillerService.selectTemplate("NDK-NEAR", "FY 2019-20");
        assertNotNull(sel.template(), "never 'no template version in force' when the card has versions");
        assertEquals(2, sel.template().version());
        assertFalse(sel.crossesVersionBoundary());
        assertEquals(List.of(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH), sel.flags());
        Map<String, Object> d = sel.flagDetails().get(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH);
        assertEquals("NDK-NEAR", d.get("card_id"));
        assertEquals("2019-04-01", d.get("period_start"));
        assertEquals("2020-03-31", d.get("period_end"));
        assertEquals("2021-01-01", d.get("template_effective_from"));
        assertEquals("2022-02-28", d.get("template_effective_to"));
        assertEquals(2, d.get("template_version"));
        assertEquals(276L, d.get("gap_days"));

        FilledTemplateResult r = fillerService.fillTemplateForIssue(fullIssue("NDK-NEAR", "FY 2019-20"), null, null);
        assertEquals("TPL-NDK-NEAR-v2", r.templateId());
        assertTrue(r.hasTemplate());
        assertTrue(r.flags().contains(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH));
        assertFalse(r.flags().contains("no_template_available"));
        assertEquals("NDK-NEAR", r.flagDetails().get(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH).get("card_id"));

        // the earlier window is nearer for FY 2018-19 (1 day after v1 ends)
        assertEquals(1, fillerService.selectTemplate("NDK-NEAR", "FY 2018-19").template().version());
    }

    @Test
    void unparseablePeriod_usesTheLatestVersionWithAWarnFlag() {
        fillerService.registerInMemoryTemplate(windowed("NDK-LATE", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2020, 12, 31)));
        fillerService.registerInMemoryTemplate(windowed("NDK-LATE", 3, LocalDate.of(2021, 1, 1), null));
        fillerService.registerInMemoryTemplate(windowed("NDK-LATE", 2, LocalDate.of(2021, 1, 1), null));

        TemplateFillService.TemplateSelection sel = fillerService.selectTemplate("NDK-LATE", "the audit period");
        assertEquals(3, sel.template().version());
        assertNull(sel.period());
        assertEquals(List.of(TemplateFillService.FLAG_PERIOD_NOT_NORMALISED), sel.flags());
        assertEquals("the audit period", sel.flagDetails().get(TemplateFillService.FLAG_PERIOD_NOT_NORMALISED).get("period"));

        FilledTemplateResult r = fillerService.fillTemplateForIssue(fullIssue("NDK-LATE", "the audit period"), null, null);
        assertEquals(3, r.templateVersion());
        assertTrue(r.hasTemplate(), "the issue still gets the template");
        assertTrue(r.flags().contains(TemplateFillService.FLAG_PERIOD_NOT_NORMALISED));
        assertFalse(r.flags().contains("no_template_available"));
    }

    @Test
    void cardWithoutTemplates_isTheOnlyNoTemplateCase() {
        TemplateFillService.TemplateSelection sel = fillerService.selectTemplate("NDK-EMPTY", "FY 2019-20");
        assertNull(sel.template());
        assertTrue(sel.reason().contains("template exists for the card"), sel.reason());
        assertTrue(sel.flags().isEmpty());

        FilledTemplateResult r = fillerService.fillTemplateForIssue(fullIssue("NDK-EMPTY", "FY 2019-20"), null, null);
        assertEquals("NONE", r.templateId());
        assertFalse(r.hasTemplate());
        assertEquals(List.of("no_template_available"), r.flags());
    }

    @Test
    void clampToGst_movesOnlyPreGstStarts() {
        PeriodNormalizer.Period fy1718 = TemplateFillService.clampToGst(PeriodNormalizer.parse("FY 2017-18").orElseThrow());
        assertEquals(LocalDate.of(2017, 7, 1), fy1718.start());
        assertEquals(LocalDate.of(2018, 3, 31), fy1718.end());
        PeriodNormalizer.Period preGst = TemplateFillService.clampToGst(PeriodNormalizer.parse("FY 2016-17").orElseThrow());
        assertEquals(LocalDate.of(2017, 7, 1), preGst.start());
        assertEquals(LocalDate.of(2017, 7, 1), preGst.end());
        PeriodNormalizer.Period later = PeriodNormalizer.parse("FY 2019-20").orElseThrow();
        assertSame(later, TemplateFillService.clampToGst(later));
    }

    @Test
    void testConditionalWhenEvaluation() {
        ReplyTemplate template = new ReplyTemplate(
                "TPL-WHEN", 1, "CARD-006",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-ALWAYS", 4, "<p>Always rendered.</p>", List.of(), null),
                        new TemplateBlock("BLK-CONDITIONAL", 4, "<p>Third party material relies block.</p>", List.of(), "notice.relies_on_third_party_material == true")
                ),
                List.of(),
                List.of(), "Summary", List.of(), null, null, null
        );
        fillerService.registerInMemoryTemplate(template);

        MatchedIssue issue = new MatchedIssue(1, "full", List.of("CARD-006"), "Why", List.of(), Map.of("period", "2022-23"), List.of(), List.of());
        MatchedNoticeInfo noticeInfoNoThirdParty = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100.0, "Tax Officer");
        noticeInfoNoThirdParty = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100.0, "Tax Officer");

        FilledTemplateResult resFalse = fillerService.fillTemplateForIssue(issue, noticeInfoNoThirdParty, null);
        assertEquals(1, resFalse.blocks().size());
        assertEquals("BLK-ALWAYS", resFalse.blocks().get(0).blockId());

        // Test relies_on_third_party_material in facts or notice map
        MatchedNoticeInfo noticeInfoThirdParty = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100.0, "Tax Officer");
        DraftingInput inputWithThirdParty = new DraftingInput(
                UUID.randomUUID(), "formal", "", "Client", "PAN", "Type", "Reg", "GSTIN", "State", "CGST", "2022-23", "2023-24",
                Map.of("relies_on_third_party_material", true), Map.of(), "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );
        FilledTemplateResult resTrue = fillerService.fillTemplateForIssue(issue, noticeInfoThirdParty, inputWithThirdParty);
        assertEquals(2, resTrue.blocks().size());
    }
}

