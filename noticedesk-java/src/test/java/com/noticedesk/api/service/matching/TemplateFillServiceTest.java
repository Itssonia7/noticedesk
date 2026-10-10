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

