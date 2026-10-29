package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.service.matching.ReplyAssemblyService.HighStakesContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.ParaReply;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Draft clean-up from the CA review of the live drafts for synthetic notices 002, 007 and 010. */
class DraftCleanupTest {

    private final ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, new ObjectMapper());

    private static final String NOTICE_TEXT = """
            Show Cause Notice under Section 74
            1. WHEREAS, intelligence was gathered.
            2. AND WHEREAS, ledgers were inspected.
            3. IT IS ALLEGED that turnover was suppressed.
            4. ACCORDINGLY, the extended period is invoked.
            5. NOW THEREFORE, show cause why:
            6. Submit your reply within 30 days.
            """;

    private static DraftingInput input(String section) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Zenith Enterprises", null, null, null, "TESTGSTIN", "Delhi",
                "CGST Act", "2020-21", null, Map.of("section", section), Map.of(), NOTICE_TEXT, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, null);
    }

    private static MatchedIssue issue(int no, String... paras) {
        return new MatchedIssue(no, "none", List.of(), "w", List.of(), Map.of("period", "2020-21"), List.of(paras), List.of());
    }

    private static MatchingResult result(Map<String, List<Integer>> paraMap, MatchedIssue... issues) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("SCN/740", null, "2021-11-20", "30 days from service", "2020-21",
                "April 2020 to March 2021", 500000.0, null);
        return new MatchingResult(info, List.of(issues), paraMap, List.of(), List.of(), "h", 1, 1, 0, 0);
    }

    private static AiIssueContent ai(int no, String writer, String para05, List<String> docs, String summary) {
        List<AiFragment> f = new ArrayList<>(List.of(new AiFragment(4, null, "<p>Ground for issue " + no + ".</p>")));
        if (para05 != null) f.add(new AiFragment(5, null, para05));
        return new AiIssueContent(no, writer, f, docs, summary);
    }

    private static String section(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    private static List<String> listItems(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<li>(.*?)</li>").matcher(html.replaceAll("<!--.*?-->", ""));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---------------------------------------------------------------- B1 documents

    @Test
    void documents_exactAndNormalisedDuplicatesRemoved_firstKept_orderPreserved() {
        List<String> out = DraftTextCleanup.dedupeDocuments(List.of(
                "Copy of the show cause notice", "Bank statements for FY 2020-21", "copy of the  Show-Cause Notice.",
                "Ledger extracts", "BANK STATEMENTS FOR FY 2020-21"));
        assertEquals(List.of("Copy of the show cause notice", "Bank statements for FY 2020-21", "Ledger extracts"), out);
    }

    @Test
    void documents_nearIdenticalEntries_keepTheWiderWordingInTheFirstPosition() {
        List<String> out = DraftTextCleanup.dedupeDocuments(List.of(
                "GSTR-3B returns for April 2020 to March 2021",
                "Reconciliation statement",
                "Copies of all GSTR-3B and GSTR-1 returns for April 2020 to March 2021",
                "GSTR-3B return for April 2020 to March 2021"));
        assertEquals(List.of("Copies of all GSTR-3B and GSTR-1 returns for April 2020 to March 2021", "Reconciliation statement"), out);
    }

    @Test
    void documents_differentPeriodsOrReturns_areNotMerged() {
        List<String> docs = List.of("GSTR-3B returns for April 2020 to March 2021", "GSTR-3B returns for April 2021 to March 2022",
                "GSTR-1 returns for April 2020 to March 2021", "Invoices");
        assertEquals(docs, DraftTextCleanup.dedupeDocuments(docs));
    }

    @Test
    void documents_listedOnceInSections10_11_14_acrossIssues() {
        Map<Integer, AiIssueContent> aiContent = Map.of(
                1, ai(1, "new_issue", null, List.of("GSTR-3B returns for April 2020 to March 2021", "Bank statements"), null),
                2, ai(2, "new_issue", null, List.of("Copies of all GSTR-3B and GSTR-1 returns for April 2020 to March 2021", "bank statements."), null));
        AssembledReplyResult r = assembly.assembleReply(result(Map.of("issue", List.of(3)), issue(1, "3"), issue(2, "3")),
                List.of(), input("Section 74"), "scn_74", aiContent, null, List.of());

        List<String> expected = List.of("Copies of all GSTR-3B and GSTR-1 returns for April 2020 to March 2021", "Bank statements");
        assertEquals(expected, listItems(section(r, 10)));
        assertEquals(expected, listItems(section(r, 14)));
        assertEquals(2, section(r, 11).split("Annexure A-").length - 1);
    }

    // ---------------------------------------------------------------- B2 missing markers

    @Test
    void markerKeys_normalised_synonymsMerged_receiptKeptSeparate() {
        assertEquals("date_of_service", DraftTextCleanup.normaliseMarkerKey("Date of Service"));
        assertEquals("date_of_service", DraftTextCleanup.normaliseMarkerKey("date_of_service_of_notice"));
        assertEquals("date_of_service", DraftTextCleanup.normaliseMarkerKey("date-and-mode-of-service"));
        assertEquals("date_of_service", DraftTextCleanup.normaliseMarkerKey("[[MISSING: Date and Mode of Service]]"));
        assertEquals("date_of_receipt", DraftTextCleanup.normaliseMarkerKey("Date of Receipt"));
        assertEquals("stage_template_for_scn_74", DraftTextCleanup.normaliseMarkerKey("stage template for scn_74"));

        assertEquals(List.of("date_of_service", "date_of_receipt", "tax_period"), DraftTextCleanup.dedupeMarkerKeys(List.of(
                "date_of_service", "Date of service of notice", "date_of_receipt", "date and mode of service", "Tax Period", "tax_period")));
    }

    @Test
    void checkMarkersList_isDeduplicated() {
        AssembledReplyResult r = new AssembledReplyResult(List.of(
                new AssembledSection(3, "03", "<p>Served on [[MISSING: date_of_service]]; received [[MISSING: date_of_receipt]].</p>"),
                new AssembledSection(4, "04", "<p>[[MISSING: Date of Service of Notice]] and [[MISSING: date and mode of service]].</p>"),
                new AssembledSection(14, "14", "<p>Within 30 days from service (date of service: [[MISSING: date_of_service]])</p>")),
                List.of(), List.of(), List.of(), List.of());
        MatchingResult mr = new MatchingResult(null, List.of(), Map.of(), List.of(), List.of(), "h", 0, 0, 0, 0);

        DraftCheckResult c = new DraftCheckService(null).runAllChecks(mr, r).stream()
                .filter(x -> x.checkName().equals("CHECK_MARKERS_LIST")).findFirst().orElseThrow();
        assertEquals(List.of("date_of_service", "date_of_receipt"), c.details().get("missing_markers"));
        assertEquals(2, c.details().get("missing_count"));
        assertFalse(c.passed());
    }

    @Test
    void longFreeTextMarkers_areShownInTheirOriginalWording_dedupedByKey() {
        String longText = "List of supplier invoices forming the differential ITC of Rs. 1,00,000/-";
        assertEquals(List.of(longText, "Date of Service", "date_of_receipt"), DraftTextCleanup.dedupeMarkers(List.of(
                "[[MISSING: " + longText + "]]", "list of  supplier invoices forming the differential ITC of Rs. 1,00,000/-",
                "Date of Service", "date_of_service_of_notice", "date and mode of service", "date_of_receipt")));
    }

    @Test
    void checkMarkersList_andMissingDataMarkers_showReadableText() {
        String longText = "List of supplier invoices forming the differential ITC of Rs. 1,00,000/-";
        AssembledReplyResult r = new AssembledReplyResult(List.of(
                new AssembledSection(4, "04", "<p>Enclose [[MISSING: " + longText + "]].</p>"),
                new AssembledSection(6, "06", "<p>See [[MISSING: list of supplier invoices forming the differential itc of rs. 1,00,000/-]].</p>")),
                List.of(), List.of(), List.of(), List.of());
        MatchingResult mr = new MatchingResult(null, List.of(), Map.of(), List.of(), List.of(), "h", 0, 0, 0, 0);

        DraftCheckResult c = new DraftCheckService(null).runAllChecks(mr, r).stream()
                .filter(x -> x.checkName().equals("CHECK_MARKERS_LIST")).findFirst().orElseThrow();
        assertEquals(List.of(longText), c.details().get("missing_markers"));
        assertEquals(1, c.details().get("missing_count"));
    }

    // ---------------------------------------------------------------- amounts rendered once

    @Test
    void duplicatedCurrencyPrefix_isCollapsed() {
        assertEquals("discrepancy of Rs 1,00,000 in GSTR-2B", TemplateFillService.collapseDuplicateCurrency("discrepancy of Rs Rs 1,00,000 in GSTR-2B"));
        assertEquals("of Rs 1,00,000", TemplateFillService.collapseDuplicateCurrency("of Rs. Rs 1,00,000"));
        assertEquals("of Rs 1,00,000", TemplateFillService.collapseDuplicateCurrency("of INR Rs 1,00,000"));
        assertEquals("of Rs 1,00,000", TemplateFillService.collapseDuplicateCurrency("of ₹ Rs 1,00,000"));
        assertEquals("of Rs -5,000", TemplateFillService.collapseDuplicateCurrency("of Rs Rs -5,000"));
        // single prefixes and ordinary words are untouched
        assertEquals("Rs 1,00,000 and Rs. 500 for Members Rs 5", TemplateFillService.collapseDuplicateCurrency("Rs 1,00,000 and Rs. 500 for Members Rs 5"));
        assertEquals("Rs. or INR", TemplateFillService.collapseDuplicateCurrency("Rs. or INR"));
    }

    @Test
    void templateWithRsBeforeAmountPlaceholder_rendersTheAmountOnce() {
        TemplateFillService fill = new TemplateFillService(null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        var res = fill.fillTextPlaceholders("<p>discrepancy of Rs {{issue.amount_tax}} and Rs. {{issue.amount_tax}}; total {{issue.amount_tax}}.</p>",
                List.of("issue.amount_tax"), Map.of("issue.amount_tax", 100000.0), "TPL-T");
        assertEquals("<p>discrepancy of Rs 1,00,000 and Rs 1,00,000; total Rs 1,00,000.</p>", res.html());
    }

    // ---------------------------------------------------------------- B3 para-wise reply

    @Test
    void paraReplies_fromSeveralIssues_oneSubEntryEach_inSubParaOrder() {
        String merged = ReplyAssemblyService.paraReplyHtml(5, List.of(
                new ParaReply("(c)", "Denied. No penalty is imposable under Section 74(1).", 1),
                new ParaReply("", "Denied. The allegations are rebutted in Section 04.", 2),
                new ParaReply("(a)", "Denied. The demand of tax is not sustainable", 3)), new ReplyAssemblyService.UsedSentences());
        assertEquals("<ul><li><strong>As to Issue #2:</strong> Denied. The allegations are rebutted in Section 04.</li>"
                + "<li><strong>As to Para 5(a):</strong> Denied. The demand of tax is not sustainable.</li>"
                + "<li><strong>As to Para 5(c):</strong> Denied. No penalty is imposable under Section 74(1).</li></ul>", merged);
    }

    @Test
    void sectionFive_mergesIssuesOnTheSameParagraph_neverGluesSubParaFragments() {
        Map<Integer, AiIssueContent> aiContent = Map.of(
                1, ai(1, "new_issue", "<ol><li><strong>Para 3:</strong> Denied. Turnover was fully declared.</li>"
                        + "<li><strong>Para 5(c):</strong> Denied. No penalty is imposable.</li></ol>", List.of(), null),
                2, ai(2, "new_issue", "<ol><li><strong>Para 3:</strong> Denied. Turnover was fully declared. Bank credits include loans.</li>"
                        + "<li>Para 5(a): Denied. The demand is time-barred.</li></ol>", List.of(), null));
        Map<String, List<Integer>> paraMap = Map.of("3", List.of(1, 2), "5", List.of(1, 2), "1", List.of(1));
        AssembledReplyResult r = assembly.assembleReply(result(paraMap, issue(1, "3", "5"), issue(2, "3", "5")),
                List.of(), input("Section 74"), "scn_74", aiContent, null, List.of());
        String s05 = section(r, 5).replaceAll("<!--.*?-->", "");

        assertTrue(s05.contains("<strong>Para 3:</strong> <ul><li><strong>As to Issue #1:</strong> Denied. Turnover was fully declared.</li>"
                + "<li><strong>As to Issue #2:</strong> Denied. Bank credits include loans.</li></ul></li>"), s05);
        assertTrue(s05.contains("<strong>Para 5:</strong> <ul><li><strong>As to Para 5(a):</strong> Denied. The demand is time-barred.</li>"
                + "<li><strong>As to Para 5(c):</strong> Denied. No penalty is imposable.</li></ul></li>"), s05);
        assertEquals(1, s05.split("Turnover was fully declared", -1).length - 1, "the same point is not repeated: " + s05);
        assertFalse(Pattern.compile("(?<!Para \\d)\\([a-z]\\):").matcher(s05).find(), "no '(c):' fragment glued mid-sentence: " + s05);
        assertTrue(s05.contains("<strong>Para 2:</strong> [[PARTNER: reply to Para 2"), "unmapped paragraphs keep the placeholder");
    }

    // ---------------------------------------------------------------- B4 executive summary

    @Test
    void highStakesExecutiveSummary_isCapped_restMovedToSection03() {
        StringBuilder longNarrative = new StringBuilder();
        for (int p = 1; p <= 4; p++) {
            longNarrative.append("<p>");
            for (int s = 1; s <= 5; s++) longNarrative.append("Paragraph ").append(p).append(" sentence ").append(s).append(" has exactly eight words here. ");
            longNarrative.append("</p>");
        }
        Map<Integer, AiIssueContent> aiContent = Map.of(1, ai(1, "high_stakes", null, List.of(), "One line on issue one."));
        AssembledReplyResult r = assembly.assembleReply(result(Map.of("issue", List.of(3)), issue(1, "3")), List.of(),
                input("Section 74"), "scn_74", aiContent, new HighStakesContent(longNarrative.toString(), "<p>Background.</p>"),
                new ArrayList<>());

        String s01 = section(r, 1);
        String narrative = s01.replaceAll("<!--(?! free-text| /free-text).*?-->", "")
                .replaceAll("(?s)^.*?<!-- free-text -->", "").replaceAll("(?s)<!-- /free-text -->.*$", "");
        assertTrue(DraftTextCleanup.wordCount(narrative) <= ReplyAssemblyService.EXEC_SUMMARY_MAX_WORDS,
                "words: " + DraftTextCleanup.wordCount(narrative));
        assertTrue(s01.contains("Paragraph 1 sentence 1"));
        assertTrue(s01.contains("One line on issue one."), "the one-line bullet per issue stays");
        String s03 = section(r, 3);
        assertTrue(s03.contains("Paragraph 4 sentence 5"), "nothing is dropped: the rest is in Section 03");
        assertFalse(s01.contains("Paragraph 4 sentence 5"));
        assertTrue(section(r, 13).contains("moved to the end of Section 03"));
    }

    @Test
    void shortExecutiveSummary_isUnchanged_andLongBulletKeepsFirstSentence() {
        assertEquals("<p>Short.</p>", DraftTextCleanup.limitWords("<p>Short.</p>", 150).kept());
        String longBullet = "The demand fails. " + "Further detail follows in many words ".repeat(10);
        assertEquals("The demand fails.", ReplyAssemblyService.oneLineSummary(longBullet));
        assertEquals("Short bullet.", ReplyAssemblyService.oneLineSummary("Short bullet."));
    }

    // ---------------------------------------------------------------- B7 procedural options

    @Test
    void antiProfiteering_partnerNote_hasRule133Points_noAdjudicationSections() {
        AssembledReplyResult r = assembly.assembleReply(result(Map.of("issue", List.of(3)), issue(1, "3")), List.of(),
                input("Section 171"), "anti_profiteering", Map.of(), null, List.of());
        String s13 = section(r, 13);
        String options = s13.substring(s13.indexOf("Procedural Objection Options"));
        options = options.substring(0, options.indexOf("</ul>"));
        assertTrue(options.contains("Rule 133") && options.contains("DGAP"), options);
        assertFalse(options.contains("75(4)") || options.contains("73(10)") || options.contains("74(10)"), options);
    }

    @Test
    void section74_partnerNote_keepsAdjudicationPoints() {
        List<String> o = ReplyAssemblyService.proceduralOptionsForStage("scn_74", "Section 74");
        assertTrue(o.stream().anyMatch(x -> x.contains("75(4)")));
        assertTrue(o.stream().anyMatch(x -> x.contains("74(10)")));
    }
}
