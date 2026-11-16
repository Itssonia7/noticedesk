package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.service.layout.ReplyLayout;
import com.noticedesk.api.service.layout.ReplyLayout.Para;
import com.noticedesk.api.service.layout.ReplyLayout.Style;
import com.noticedesk.api.service.layout.ReplyLayout.Table;
import com.noticedesk.api.testsupport.SyntheticS73Notice;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run at c82df8b on the synthetic Section 73 notice: "Issue 1: The records of M/s Yamuna Textile Mills Private
 * Limited ("the noticee") for FY 2021-22" (the narrative intro), "Issue 7: Objection 6: Short payment of tax owing to
 * GSTR-3B of September 2021. The GSTR-3B" (cut mid-sentence) and two issues both "Objection 5: Late filing of returns".
 */
class IssueTitlesTest {

    private static final List<String> EXPECTED = List.of(
            "Excess ITC in GSTR-3B over GSTR-2B",
            "ITC on goods not covered by the registration certificate",
            "Non-payment of tax under reverse charge on freight",
            "Under-valuation of outward supplies",
            "Late filing of returns - late fee and interest (Sections 47 and 50)",
            "Late filing of returns - penalty under Section 125",
            "Short payment of tax owing to GSTR-3B of September 2021");

    @Test
    void liveNotice_headingsComeFromTheObjections_uniqueAndWithoutTheClientName() {
        var r = SyntheticS73Notice.assemble(SyntheticS73Notice.matching(SyntheticS73Notice.issues(), SyntheticS73Notice.TAX_TOTAL),
                SyntheticS73Notice.aiFor(SyntheticS73Notice.issues()), List.of());
        ReplyLayout l = SyntheticS73Notice.layout(r);

        List<String> headings = l.reply().stream().filter(b -> b instanceof Para p && p.style() == Style.HEADING)
                .map(b -> ReplyLayout.plain(((Para) b).text())).toList();
        List<String> titles = new ArrayList<>();
        for (String h : headings) titles.add(h.replaceFirst("^Issue \\d+: ", "").replaceFirst(" \\(Rs [\\d,]+\\)$", "")
                .replaceFirst(", consequential to Issue \\d+$", ""));
        assertEquals(EXPECTED, titles, headings.toString());

        Table summary = l.reply().stream().filter(b -> b instanceof Table t && t.header().contains("Ground")).map(b -> (Table) b)
                .findFirst().orElseThrow();
        List<String> grounds = summary.rows().stream().filter(row -> !row.get(0).isBlank()).map(row -> ReplyLayout.plain(row.get(1))).toList();
        assertEquals(EXPECTED, grounds, "the summary table's Ground column uses the same titles");

        for (String t : titles) {
            assertFalse(t.contains("Yamuna") || t.contains("noticee") || t.contains("records of"), t);
            assertFalse(t.startsWith("Objection"), t);
            assertTrue(t.length() <= IssueTitles.MAX_CHARS, t);
        }
    }

    @Test
    void headingSentence_stopsAtTheRealFullStop_notAtAbbreviations_andIsCappedAtAWord() {
        assertEquals("Non-payment of tax under Notification No. 13/2017-Central Tax (Rate) on freight",
                IssueTitles.headingOf("Issue No. 3: Non-payment of tax under Notification No. 13/2017-Central Tax (Rate) on freight. No tax was paid."));
        assertEquals("Excess ITC of Rs. 4,44,520 availed u/s. 16", IssueTitles.headingOf("Para 2 - Excess ITC of Rs. 4,44,520 availed u/s. 16. The noticee ..."));
        assertEquals("Blocked credit under Section 17(5)(d) on cement and steel",
                IssueTitles.headingOf("Ground 1: Blocked credit under Section 17(5)(d) on cement and steel. Details follow."));
        String longHeading = "Objection 9: Wrong availment of input tax credit on inward supplies received from suppliers whose"
                + " registrations were cancelled retrospectively during the relevant period. The details follow.";
        String t = IssueTitles.headingOf(longHeading);
        assertTrue(t.length() <= IssueTitles.MAX_CHARS, t);
        assertFalse(t.endsWith("…") || t.endsWith("...") || t.endsWith(" whose"), t);
        assertTrue(longHeading.contains(t + " "), "cut at a word boundary: " + t);
        // a narrative intro paragraph is never a title
        assertNull(IssueTitles.headingOf("The records of the noticee for FY 2021-22 were audited under Section 65 of the CGST Act, 2017."
                + " The final audit report was issued on 09-09-2024."));
    }

    @Test
    void fallbacks_cardTitleThenLegalSection_neverTheClientName() {
        MatchedIssue intro = SyntheticS73Notice.issue(1, "demand", "Section 16(2)(aa)", "444520", List.of("1"), null);
        String text = SyntheticS73Notice.text();
        assertEquals("ITC beyond GSTR-2B", IssueTitles.title(intro, text, "ITC beyond GSTR-2B", SyntheticS73Notice.CLIENT));
        assertEquals("Demand under Section 16(2)(aa)", IssueTitles.title(intro, text, null, SyntheticS73Notice.CLIENT));
        assertEquals("Demand under Section 16(2)(aa)",
                IssueTitles.title(intro, text, "Reply for Yamuna Textile Mills Private Limited", SyntheticS73Notice.CLIENT));
    }

    @Test
    void sameTitleWithoutAQualifier_isNumbered() {
        Map<Integer, String> titles = new LinkedHashMap<>();
        titles.put(1, "Late filing");
        titles.put(2, "Late filing");
        Map<Integer, MatchedIssue> issues = Map.of(
                1, SyntheticS73Notice.issue(1, "demand", null, "100", List.of("6"), null),
                2, SyntheticS73Notice.issue(2, "demand", null, "200", List.of("6"), null));
        Map<Integer, String> u = IssueTitles.unique(titles, issues);
        assertEquals("Late filing (part 1)", u.get(1));
        assertEquals("Late filing (part 2)", u.get(2));
    }
}
