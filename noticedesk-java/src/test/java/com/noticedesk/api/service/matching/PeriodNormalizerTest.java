package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.PeriodNormalizer.Period;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PeriodNormalizerTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "April 2020 to March 2021      | 2020-04-01 | 2021-03-31",
            "Apr-2020 to Mar-2021          | 2020-04-01 | 2021-03-31",
            "April, 2020 - March, 2021     | 2020-04-01 | 2021-03-31",
            "FY 2020-21                    | 2020-04-01 | 2021-03-31",
            "2020-21                       | 2020-04-01 | 2021-03-31",
            "F.Y. 2020-2021                | 2020-04-01 | 2021-03-31",
            "FY2020-21                     | 2020-04-01 | 2021-03-31",
            "01.04.2020 to 31.03.2021      | 2020-04-01 | 2021-03-31",
            "01-04-2020 to 31-03-2021      | 2020-04-01 | 2021-03-31",
            "01/04/2020 - 31/03/2021       | 2020-04-01 | 2021-03-31",
            "July 2019                     | 2019-07-01 | 2019-07-31",
            "Jul-19                        | 2019-07-01 | 2019-07-31",
            "07/2019                       | 2019-07-01 | 2019-07-31",
            "2019-07                       | 2019-07-01 | 2019-07-31",
            "04/2020 to 03/2021            | 2020-04-01 | 2021-03-31",
            "July 2017 to March 2020       | 2017-07-01 | 2020-03-31",
            "2018-19 to 2020-21            | 2018-04-01 | 2021-03-31",
            "February 2020                 | 2020-02-01 | 2020-02-29",
    })
    void parsesEveryFormat(String text, String start, String end) {
        Optional<Period> p = PeriodNormalizer.parse(text);
        assertTrue(p.isPresent(), text);
        assertEquals(LocalDate.parse(start), p.get().start(), text);
        assertEquals(LocalDate.parse(end), p.get().end(), text);
    }

    @Test
    void unreadableTextIsEmpty() {
        assertTrue(PeriodNormalizer.parse(null).isEmpty());
        assertTrue(PeriodNormalizer.parse("").isEmpty());
        assertTrue(PeriodNormalizer.parse("the period under audit").isEmpty());
        assertTrue(PeriodNormalizer.parse("2020-25").isEmpty(), "not a financial year");
        assertTrue(PeriodNormalizer.parse("31.03.2021 to 01.04.2020").isEmpty(), "end before start");
    }

    @Test
    void multiYearRangeIsDetected() {
        assertTrue(PeriodNormalizer.parse("July 2017 to March 2020").orElseThrow().spansMultipleFinancialYears());
        assertFalse(PeriodNormalizer.parse("FY 2020-21").orElseThrow().spansMultipleFinancialYears());
    }

    // ------------------------------------------------------------------ template version selection

    private static ReplyTemplate tpl(int version, String from, String to) {
        return new ReplyTemplate("TPL-X", version, "CARD-X", from != null ? LocalDate.parse(from) : null,
                to != null ? LocalDate.parse(to) : null, "active",
                List.of(new TemplateBlock("B" + version, 4, "<p>Version " + version + " text.</p>", List.of(), null)),
                List.of(), List.of(), "Version " + version + ".", List.of(), null, null, null);
    }

    private static TemplateFillService serviceWithTwoVersions() {
        TemplateFillService s = new TemplateFillService(null, null, new ObjectMapper());
        s.registerInMemoryTemplate(tpl(1, "2017-07-01", "2020-12-31"));
        s.registerInMemoryTemplate(tpl(2, "2021-01-01", null));
        return s;
    }

    // v1 2017-07-01..2020-12-31, v2 2021-01-01..open: the version in force on the period's END is preferred
    @ParameterizedTest(name = "{0} -> v{1}")
    @CsvSource(delimiter = '|', value = {
            "April 2020 to March 2021 | 2",
            "Apr-2020 to Mar-2021     | 2",
            "FY 2020-21               | 2",
            "F.Y. 2020-2021           | 2",
            "01.04.2020 to 31.03.2021 | 2",
            "April 2020 to December 2020 | 1",
            "July 2019                | 1",
            "07/2019                  | 1",
            "FY 2019-20               | 1",
            "FY 2021-22               | 2",
            "January 2021             | 2",
    })
    void selectsTheOverlappingVersionInForceOnThePeriodEnd(String period, int version) {
        assertEquals(version, serviceWithTwoVersions().selectTemplateForCardAndPeriod("CARD-X", period).version());
    }

    @Test
    void periodCrossingAVersionBoundaryUsesTheEndVersionAndFlagsWarn() {
        TemplateFillService s = serviceWithTwoVersions();
        TemplateFillService.TemplateSelection sel = s.selectTemplate("CARD-X", "April 2020 to March 2021");
        assertEquals(2, sel.template().version());
        assertTrue(sel.crossesVersionBoundary());
        assertTrue(sel.flags().isEmpty(), "an overlapping version needs no mismatch flag");
        assertFalse(s.selectTemplate("CARD-X", "July 2019").crossesVersionBoundary());

        MatchedIssue issue = new MatchedIssue(1, "full", List.of("CARD-X"), "w", List.of(),
                Map.of("period", "April 2020 to March 2021"), List.of(), List.of());
        TemplateFillService.FilledTemplateResult r = s.fillTemplateForIssue(issue, null, null);
        assertTrue(r.flags().contains(TemplateFillService.PERIOD_CROSSES_TEMPLATE_VERSION_FLAG));
        assertFalse(r.flags().contains(TemplateFillService.FLAG_TEMPLATE_WINDOW_MISMATCH));
        assertEquals("TPL-X", r.templateId());
        assertEquals(2, r.templateVersion());
    }

    @Test
    void unreadablePeriodGetsTheLatestVersionWithAWarnFlag() {
        TemplateFillService.TemplateSelection sel = serviceWithTwoVersions().selectTemplate("CARD-X", "the audit period");
        assertNotNull(sel.template());
        assertEquals(2, sel.template().version());
        assertNull(sel.reason());
        assertEquals(List.of(TemplateFillService.FLAG_PERIOD_NOT_NORMALISED), sel.flags());
        assertEquals("no active, provisional or draft template exists for the card",
                serviceWithTwoVersions().selectTemplate("CARD-NONE", "FY 2020-21").reason());
    }
}
