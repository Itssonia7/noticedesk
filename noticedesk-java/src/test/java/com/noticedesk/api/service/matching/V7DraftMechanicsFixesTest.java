package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiParagraph;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.ParaReply;
import com.noticedesk.api.service.matching.ReplyAssemblyService.UsedSentences;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Mechanical defects seen in the v7 drafts of a three-ground refund notice (RFD-08): client name from the notice
 * header (A), marker prefixes and duplicates (B), writer enumerators and lettered headings (C), para-wise sub-entries,
 * repeats and inline bullets (D), duplicate citations (E) and the refund prayer (F). Synthetic text, fictional
 * parties, allow-listed GSTINs; no model call.
 */
class V7DraftMechanicsFixesTest {

    private static final String PARTY = "27AAAAA0000A1Z5";
    private static final String SUPPLIER = "27BBBBB1111B1Z5";

    private static final String RFD_TEXT = """
            OFFICE OF THE ASSISTANT COMMISSIONER OF STATE TAX
            FORM GST RFD-08
            Notice for rejection of application for refund
            Name of the RTP: M/s ORBIT LAMPS PRIVATE LIMITED
            GSTIN: 27AAAAA0000A1Z5
            Refund Application ARN AA2701260000001 for Rs.1,78,63,659/- for the period JAN-2026.
            1. The applicant filed the above refund application.
            2. (a) ITC in GSTR-3B exceeds GSTR-2B. (b) ITC from a supplier whose registration is cancelled.
            (c) ITC not in furtherance of business.
            3. The supplier M/s Beta Industries, GSTIN 27BBBBB1111B1Z5, has not filed its returns.
            4. You are directed to reply within 15 days.
            """;

    private final ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, new ObjectMapper());

    private static DraftingInput input(String clientName, String text, String documentType) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, clientName, null, null, "Regular", PARTY, "Maharashtra",
                "CGST Act", "2025-26", null, Map.of("document_type", documentType), Map.of(), text, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static MatchedIssue ground(int no, double amount, String label, String para) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("period", "JAN-2026");
        facts.put("amount", amount);
        facts.put("legal_section", "Section 54(3)");
        if (label != null) facts.put("label", label);
        return new MatchedIssue(no, "none", "demand", List.of(), List.of(), "ground " + no, List.of(), facts, List.of(para),
                List.of(), List.of(), null);
    }

    private static MatchingResult matching(Map<String, List<Integer>> paraMap, MatchedIssue... issues) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("RFD08/001", null, "2026-02-20", "15 days", "2025-26", "JAN-2026", null, null);
        return new MatchingResult(info, List.of(issues), paraMap, List.of(), List.of(), "h", 1, 1, 0, 0);
    }

    private static AiIssueContent ai(int no, String sec04, String sec05) {
        List<AiFragment> f = new ArrayList<>(List.of(new AiFragment(4, null, sec04)));
        if (sec05 != null) f.add(new AiFragment(5, null, sec05));
        return new AiIssueContent(no, "new_issue", f, List.of(), "summary " + no);
    }

    private static String section(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml().replaceAll("<!--.*?-->", "");
    }

    private static String allSections(AssembledReplyResult r) {
        StringBuilder sb = new StringBuilder();
        r.sections().forEach(s -> sb.append(s.bodyHtml()).append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- A. client legal name from the notice header

    @Test
    void a_legalNameIsReadNextToTheTaxpayerGstin() {
        assertEquals("ORBIT LAMPS PRIVATE LIMITED", NoticeHeaderFacts.taxpayerLegalName(RFD_TEXT, PARTY));
        assertEquals("Orbit Lamps Private Limited", NoticeHeaderFacts.taxpayerLegalName(
                "FORM GST RFD-08\nIn case of M/s Orbit Lamps Private Limited, GSTIN-27AAAAA0000A1Z5\nRefund ...", PARTY));
        assertEquals("ORBIT LAMPS PRIVATE LIMITED", NoticeHeaderFacts.taxpayerLegalName(
                "To,\nGSTIN: 27AAAAA0000A1Z5\nLegal Name: ORBIT LAMPS PRIVATE LIMITED\nAddress: Plot 1, Pune", PARTY));
    }

    @Test
    void a_aSuppliersNameIsNeverTaken_andUnsureKeepsTheMarker() {
        // the only M/s name sits next to the supplier's GSTIN
        String supplierOnly = "FORM GST RFD-08\nGSTIN of the applicant: 27AAAAA0000A1Z5\n"
                + "1. The applicant availed ITC from invoices of M/s Beta Industries (GSTIN 27BBBBB1111B1Z5).";
        assertNull(NoticeHeaderFacts.taxpayerLegalName(supplierOnly, PARTY));
        assertEquals("Beta Industries", NoticeHeaderFacts.taxpayerLegalName(supplierOnly, SUPPLIER), "read only next to its own GSTIN");
        // two different names next to the taxpayer GSTIN: unsure
        assertNull(NoticeHeaderFacts.taxpayerLegalName(
                "Name of the RTP: Orbit Lamps Private Limited\nGSTIN 27AAAAA0000A1Z5 M/s Orbit Electricals LLP", PARTY));
        assertNull(NoticeHeaderFacts.taxpayerLegalName(RFD_TEXT, null), "no taxpayer GSTIN, no name");
        assertNull(NoticeHeaderFacts.taxpayerLegalName("M/s the supplier has availed credit 27AAAAA0000A1Z5", PARTY),
                "a sentence is not a name");
    }

    @Test
    void a_assemblyUsesTheNoticeNameWhenTheClientRecordHasNone() {
        AssembledReplyResult r = assembly.assembleReply(matching(Map.of("2", List.of(1)), ground(1, 100000, null, "2")),
                List.of(), input(null, RFD_TEXT, "rfd_08"), "rfd_08", Map.of(1, ai(1, "<p>Ground.</p>", null)), null, List.of());
        String all = allSections(r);
        assertFalse(all.contains("[[MISSING: client_legal_name]]"), all);
        assertTrue(section(r, 2).contains("The taxpayer M/s ORBIT LAMPS PRIVATE LIMITED has received"), section(r, 2));
        assertTrue(section(r, 3).startsWith("<p>M/s ORBIT LAMPS PRIVATE LIMITED (GSTIN: " + PARTY + ")"), section(r, 3));

        AssembledReplyResult noName = assembly.assembleReply(matching(Map.of("1", List.of(1)), ground(1, 100000, null, "1")),
                List.of(), input(null, "Notice text with no party name. GSTIN 27AAAAA0000A1Z5", "rfd_08"), "rfd_08",
                Map.of(1, ai(1, "<p>Ground.</p>", null)), null, List.of());
        assertTrue(section(noName, 2).contains("[[MISSING: client_legal_name]]"), "unsure keeps the marker");
    }

    // ---------------------------------------------------------------- B. marker prefixes and duplicates

    @Test
    void b_visibleMarkersHaveNoPrefix_andTheSameBlankIsListedOnce() {
        assertEquals("supplier_gstr1_filing_date", DraftTextCleanup.markerName("issue.supplier_gstr1_filing_date"));
        assertEquals("tax_period", DraftTextCleanup.markerName("notice.tax_period"));
        assertEquals("client_legal_name", DraftTextCleanup.markerName("client.legal_name"));
        assertEquals("<p>[[MISSING: supplier_gstr1_filing_date]] and [[MISSING: client_gstin]]</p>",
                DraftTextCleanup.stripMarkerPrefixes("<p>[[MISSING: issue.supplier_gstr1_filing_date]] and [[MISSING: client.gstin]]</p>"));
        assertEquals(List.of("supplier_gstr1_filing_date", "date_of_receipt"), DraftTextCleanup.dedupeMarkers(List.of(
                "issue.supplier_gstr1_filing_date", "supplier_gstr1_filing_date", "facts.supplier_gstr1_filing_date",
                "date_of_receipt", "notice.date_of_receipt")));
    }

    @Test
    void b_draftCheckListsEachBlankOnce_withoutPrefix() {
        AiIssueContent c = ai(1, "<p>Filed on [[MISSING: issue.supplier_gstr1_filing_date]].</p>"
                + "<p>The supplier filed GSTR-1 on [[MISSING: supplier_gstr1_filing_date]].</p>", null);
        AssembledReplyResult r = assembly.assembleReply(matching(Map.of("1", List.of(1)), ground(1, 100000, null, "1")),
                List.of(), input("Orbit Lamps Private Limited", RFD_TEXT, "rfd_08"), "rfd_08", Map.of(1, c), null, List.of());
        String all = allSections(r);
        assertFalse(all.contains("[[MISSING: issue."), "no prefix in the visible text: " + all);
        assertTrue(all.contains("[[MISSING: supplier_gstr1_filing_date]]"), all);

        List<DraftCheckResult> checks = new DraftCheckService(null).runAllChecks(
                matching(Map.of("1", List.of(1)), ground(1, 100000, null, "1")), r, "Section 54", "rfd_08");
        DraftCheckResult markers = checks.stream().filter(x -> "CHECK_MARKERS_LIST".equals(x.checkName())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> listed = (List<String>) markers.details().get("missing_markers");
        assertEquals(1, listed.stream().filter(m -> m.contains("supplier_gstr1_filing_date")).count(), listed.toString());
        assertTrue(listed.contains("supplier_gstr1_filing_date"), listed.toString());
    }

    // ---------------------------------------------------------------- C. writer enumerators and lettered headings

    @Test
    void c_writerEnumeratorIsRemovedBeforeOurNumbering() {
        for (String p : List.of("5. Section 16(1) allows credit.", "(5) Section 16(1) allows credit.", "5) Section 16(1) allows credit.",
                "(a) Section 16(1) allows credit.", "A. Section 16(1) allows credit.", "(iv) Section 16(1) allows credit.",
                "<strong>5.</strong> Section 16(1) allows credit.", "4.1 Section 16(1) allows credit.")) {
            AiParagraph a = ReplyAssemblyService.aiParagraph(p);
            assertFalse(a.heading(), p);
            assertEquals("Section 16(1) allows credit.", a.text().replaceAll("<[^>]+>", "").trim(), p);
        }
        assertEquals("1.5 lakh of credit is in dispute.", ReplyAssemblyService.aiParagraph("1.5 lakh of credit is in dispute.").text());
        assertEquals("Section 16(1) allows credit.", ReplyAssemblyService.aiParagraph("Section 16(1) allows credit.").text());
    }

    @Test
    void c_shortLetteredLineIsAnUnnumberedSubHeading() {
        AiParagraph h = ReplyAssemblyService.aiParagraph("A. The nature of the present proceedings");
        assertTrue(h.heading());
        assertEquals("A. The nature of the present proceedings", h.text());
        assertFalse(ReplyAssemblyService.aiParagraph("A. The nature of the present proceedings is a refund claim.").heading(),
                "a sentence ending in a full stop is a paragraph");
        assertFalse(ReplyAssemblyService.aiParagraph("1. The nature of the present proceedings").heading(), "a number is not a letter");

        String html = "<p>A. The nature of the present proceedings</p><p>1. The ground is that the credit exceeds GSTR-2B.</p>"
                + "<p>5. Section 16(1) allows credit.</p>";
        AssembledReplyResult r = assembly.assembleReply(matching(Map.of("1", List.of(1)), ground(1, 100000, null, "1")),
                List.of(), input("Orbit Lamps Private Limited", RFD_TEXT, "rfd_08"), "rfd_08", Map.of(1, ai(1, html, null)), null, List.of());
        String s04 = section(r, 4);
        assertTrue(s04.contains("<h4>A. The nature of the present proceedings</h4><p>4.1 The ground is that the credit exceeds GSTR-2B.</p>"
                + "<p>4.2 Section 16(1) allows credit.</p>"), s04);
        assertFalse(s04.matches("(?s).*\\d+\\.\\d+ (?:\\d+\\.|[A-Z]\\.) .*"), "no double numbering: " + s04);
    }

    // ---------------------------------------------------------------- D. para-wise sub-entries, repeats, bullets

    @Test
    void d_severalIssuesOnOneParagraphAreSeparateSubEntries_andRepeatsAreDropped() {
        Map<Integer, AiIssueContent> aiContent = Map.of(
                1, ai(1, "<p>Ground 1.</p>", "<ol><li>Para 2(a): Denied. The credit in GSTR-3B matches the invoices received in the period.</li>"
                        + "<li>Para 3: Denied. The credit in GSTR-3B matches the invoices received in the period.</li></ol>"),
                2, ai(2, "<p>Ground 2.</p>", "<ol><li>Para 2(b): Denied. The supplier was registered on the invoice date.</li></ol>"),
                3, ai(3, "<p>Ground 3.</p>", "<ol><li>Para 2(c): Denied. The services were used to make taxable supplies.</li>"
                        + "<li>Para 4: Noted. The services were used to make taxable supplies. The reply is filed in time.</li></ol>"));
        AssembledReplyResult r = assembly.assembleReply(
                matching(Map.of("2", List.of(1, 2, 3), "3", List.of(1), "4", List.of(3)),
                        ground(1, 100, null, "2"), ground(2, 200, null, "2"), ground(3, 300, null, "2")),
                List.of(), input("Orbit Lamps Private Limited", RFD_TEXT, "rfd_08"), "rfd_08", aiContent, null, List.of());
        String s05 = section(r, 5);

        assertTrue(s05.contains("<strong>Para 2:</strong> <ul>"
                + "<li><strong>As to Para 2(a):</strong> Denied. The credit in GSTR-3B matches the invoices received in the period.</li>"
                + "<li><strong>As to Para 2(b):</strong> Denied. The supplier was registered on the invoice date.</li>"
                + "<li><strong>As to Para 2(c):</strong> Denied. The services were used to make taxable supplies.</li></ul>"), s05);
        assertTrue(s05.contains("<strong>Para 3:</strong> Denied. See also the reply to Para 2 above.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 4:</strong> Noted. The reply is filed in time. See also the reply to Para 2 above.</li>"), s05);
        assertEquals(1, s05.split("matches the invoices received", -1).length - 1, s05);
        assertEquals(1, s05.split("used to make taxable supplies", -1).length - 1, s05);
    }

    @Test
    void d_nearRepeatIsDropped_shortSentencesInOtherParagraphsAreKept() {
        UsedSentences used = new UsedSentences();
        ReplyAssemblyService.paraReplyHtml(2, List.of(new ParaReply("", "Denied. The supplier filed every return for the period on time.", 1)), used);
        String p3 = ReplyAssemblyService.paraReplyHtml(3, List.of(new ParaReply("",
                "Denied. The supplier had filed every return for the period on time. Turnover was declared.", 1)), used);
        assertEquals("Denied. Turnover was declared. See also the reply to Para 2 above.", p3);
        String p4 = ReplyAssemblyService.paraReplyHtml(4, List.of(new ParaReply("", "Denied. Turnover was declared.", 1)), used);
        assertEquals("Denied. Turnover was declared.", p4, "a short sentence may recur under another paragraph");
        String p5 = ReplyAssemblyService.paraReplyHtml(5, List.of(new ParaReply("", "The supplier filed every return for the period on time.", 1)), used);
        assertEquals("Please refer to the reply to Para 2 above.", p5);
    }

    @Test
    void d_inlineBulletsBecomeARealList() {
        assertEquals("The entries are inconsistent:<ul><li>the CGST entries differ;</li><li>the SGST entries differ.</li></ul>",
                ReplyAssemblyService.inlineBulletsToList("The entries are inconsistent:  * the CGST entries differ;  * the SGST entries differ."));
        assertEquals("No list here.", ReplyAssemblyService.inlineBulletsToList("No list here."));
        String one = ReplyAssemblyService.paraReplyHtml(2, List.of(new ParaReply("(a)",
                "Denied. The entries are inconsistent:  * the CGST entries differ;  * the SGST entries differ.", 1)), new UsedSentences());
        assertEquals("As to Para 2(a): Denied. The entries are inconsistent:<ul><li>the CGST entries differ;</li>"
                + "<li>the SGST entries differ.</li></ul>", one);
    }

    // ---------------------------------------------------------------- E. duplicate citations

    @Test
    void e_caseNameSpellingsAreOneCitation() {
        String a = CitationVerificationAgent.citationIdentity("Arise India Ltd v Commissioner of Trade & Taxes");
        assertEquals(a, CitationVerificationAgent.citationIdentity("Arise India Limited v. Commissioner of Trade and Taxes, Delhi"));
        assertEquals(a, CitationVerificationAgent.citationIdentity("M/s Arise India Pvt. Ltd. vs Commr. of Trade & Taxes, New Delhi"));
        assertEquals(a, CitationVerificationAgent.citationIdentity("ARISE INDIA LIMITED V/S COMMISSIONER OF TRADE AND TAXES (2017) 3 SCC 1"));
        assertNotEquals(a, CitationVerificationAgent.citationIdentity("Arise India Ltd v Union of India"));

        List<RawAiCitation> deduped = CitationVerificationAgent.dedupeCitations(List.of(
                new RawAiCitation("Arise India Ltd v Commissioner of Trade & Taxes", "Delhi High Court", 2017, null, "ITC"),
                new RawAiCitation("Arise India Limited v. Commissioner of Trade and Taxes, Delhi", null, null, null, null)));
        assertEquals(1, deduped.size());
    }

    @Test
    void e_verificationChecksEachCaseOnce_andTheCheckCountsItOnce() {
        CitationVerificationAgent agent = new CitationVerificationAgent(new ObjectMapper(), null, null, null, mock(IndianKanoonClient.class));
        var result = agent.verifyAiCitations(List.of(
                new RawAiCitation("Arise India Ltd v Commissioner of Trade & Taxes", "Delhi High Court", 2017, null, "ITC"),
                new RawAiCitation("Arise India Limited v. Commissioner of Trade and Taxes, Delhi", "Delhi High Court", 2017, null, "ITC")),
                UUID.randomUUID());
        assertEquals(1, result.citations().size(), "one case, verified once");

        VerifiedCitation v1 = new VerifiedCitation("Arise India Ltd v Commissioner of Trade & Taxes", null, null, "VERIFIED", null, null,
                1.0, "passed", null, null, null, null);
        VerifiedCitation v2 = new VerifiedCitation("Arise India Limited v. Commissioner of Trade and Taxes, Delhi", null, null, "VERIFIED",
                null, null, 1.0, "passed", null, null, null, null);
        DraftCheckResult verified = new DraftCheckService(null).citationChecks(List.of(v1, v2)).get(0);
        assertEquals("CHECK_AI_CITATIONS_VERIFIED", verified.checkName());
        assertEquals(1, verified.details().get("total"));
    }

    // ---------------------------------------------------------------- F. refund prayer

    @Test
    void f_refundPrayerNamesEachGroundWithItsAmount_sanctionsTheRefund_andAsksForAHearing() {
        Map<Integer, AiIssueContent> aiContent = Map.of(
                1, ai(1, "<p>The credit matches the invoices.</p>", null),
                2, ai(2, "<p>The supplier was registered on the invoice date.</p>", null),
                3, ai(3, "<p>The services were used for taxable supplies.</p>", null));
        AssembledReplyResult r = assembly.assembleReply(
                matching(Map.of("2", List.of(1, 2, 3)),
                        ground(1, 10000000, "Excess ITC in GSTR-3B over GSTR-2B", "2"),
                        ground(2, 5000000, "ITC from a supplier whose registration is cancelled", "2"),
                        ground(3, 2863659, "ITC not in furtherance of business", "2")),
                List.of(), input("Orbit Lamps Private Limited", RFD_TEXT, "rfd_08"), "rfd_08", aiContent, null, List.of());
        String s12 = section(r, 12);

        assertTrue(s12.contains("drop the proposed rejection of the refund of Rs 1,78,63,659 proposed in notice reference RFD08/001"), s12);
        assertTrue(s12.contains("<li>excess ITC in GSTR-3B over GSTR-2B (Issue #1) (Rs 1,00,00,000);</li>"), s12);
        assertTrue(s12.contains("<li>ITC from a supplier whose registration is cancelled (Issue #2) (Rs 50,00,000);</li>"), s12);
        assertTrue(s12.contains("<li>ITC not in furtherance of business (Issue #3) (Rs 28,63,659).</li>"), s12);
        assertTrue(s12.contains("sanction the refund of Rs 1,78,63,659 as claimed"), s12);
        assertTrue(s12.contains("opportunity of personal hearing before any order adverse"), s12);
        assertFalse(s12.contains("outside Section 54"), s12);
        assertFalse(s12.contains("Section 54"), "no issue argues the Section 54 point: " + s12);
        assertFalse(s12.contains("[[MISSING"), s12);
    }

    @Test
    void f_section54WordingOnlyForTheGroundWhoseTextArguesIt() {
        Map<Integer, AiIssueContent> aiContent = Map.of(
                1, ai(1, "<p>The credit matches the invoices.</p>", null),
                2, ai(2, "<p>A cancellation of the supplier's registration after the supply is outside the scope of Section 54"
                        + " and cannot ground a rejection.</p>", null));
        AssembledReplyResult r = assembly.assembleReply(
                matching(Map.of("2", List.of(1, 2)), ground(1, 100000, null, "2"), ground(2, 200000, null, "2")),
                List.of(), input("Orbit Lamps Private Limited", RFD_TEXT, "rfd_08"), "rfd_08", aiContent, null, List.of());
        String s12 = section(r, 12);
        assertTrue(s12.contains("<li>the ground at Issue #1 (Rs 1,00,000);</li>"), s12);
        assertTrue(s12.contains("<li>the ground at Issue #2 (Rs 2,00,000), which is not a ground for rejection under Section 54"
                + " of the CGST Act, 2017 and the Rules made thereunder.</li>"), s12);
        assertTrue(ReplyAssemblyService.ARGUES_OUTSIDE_SECTION_54.matcher("This is not a valid ground for rejection under Section 54(3).").find());
        assertFalse(ReplyAssemblyService.ARGUES_OUTSIDE_SECTION_54.matcher("Refund is claimed under Section 54(3).").find());
    }
}
