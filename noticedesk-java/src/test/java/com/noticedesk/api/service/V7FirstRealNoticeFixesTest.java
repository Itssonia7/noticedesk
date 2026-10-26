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
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The first real notice run (an RFD-08 refund show cause, no reference or date, three grounds, supplier GSTINs in the
 * body) replayed with every external call mocked: the taxpayer GSTIN, state and financial year are set by code, the
 * statutory paragraph is Noted, the refund amounts are read from the notice, and the documents list is merged. Fictional
 * party; synthetic, allow-listed GSTINs.
 */
class V7FirstRealNoticeFixesTest {

    private static final String PARTY = "27AAAAA0000A1Z5";
    private static final String SUPPLIER = "27BBBBB1111B1Z5";

    private static final String TEXT = """
            GOVERNMENT OF MAHARASHTRA
            OFFICE OF THE ASSISTANT COMMISSIONER OF STATE TAX
            FORM GST RFD-08
            Notice for rejection of application for refund

            In case of M/s Noticedesk Technologies Private Limited, GSTIN-27AAAAA0000A1Z5

            Refund Application ARN AA2701260000001 dated 10.02.2026 for Rs.1,78,63,659/- for the period JAN-2026.

            1. The applicant filed the above refund application on account of inverted duty structure.
            Contravention of the Law:
            2. Section 54(3) of the CGST Act, 2017 provides that a registered person may claim refund of unutilised input tax credit.
            3. The inward supplies from M/s Alpha Traders, GSTIN 27BBBBB1111B1Z5, are not reflected in GSTR-2B.
            4. M/s Beta Industries, GSTIN 24CCCCC2222C1Z5, has not filed its returns for the period.
            5. Input services of M/s Gamma Agencies, GSTIN 29DDDDD3333D1Z5, are not eligible for the refund.
            6. You are directed to reply within 15 days.
            """;

    private final ObjectMapper om = new ObjectMapper();

    private static MatchedIssue ground(int no, double amount, String para) {
        return new MatchedIssue(no, "none", "demand", List.of(), List.of(), "ground " + no, List.of(),
                Map.of("period", "JAN-2026", "amount", amount, "legal_section", "Section 54(3)"), List.of(para), List.of(),
                List.of(), null);
    }

    /** The matcher's reading: a supplier's GSTIN, no financial year, no authority, every paragraph denied. */
    private static MatchingResult matching() {
        MatchedNoticeInfo info = new MatchedNoticeInfo(null, null, null, null, null, "JAN-2026", null, null, SUPPLIER);
        Map<String, String> stance = new LinkedHashMap<>();
        stance.put("1", "admitted");
        for (String p : List.of("2", "3", "4", "5")) stance.put(p, "denied");
        stance.put("6", "not_applicable");
        return new MatchingResult(info, List.of(ground(1, 10000000, "3"), ground(2, 5000000, "4"), ground(3, 2863659, "5")),
                Map.of("2", List.of(1, 2, 3), "3", List.of(1), "4", List.of(2), "5", List.of(3)), List.of(), List.of(), "hash",
                1, 1, 0, 0, Map.of(), stance);
    }

    private static final List<List<String>> WRITER_DOCUMENTS = List.of(
            List.of("Copy of the refund application in Form GST RFD-01", "Purchase invoices of the suppliers",
                    "GSTR-2B for January 2026"),
            List.of("Refund application in FORM GST RFD-01 with all annexures", "Copies of purchase invoices issued by the suppliers",
                    "Copy of GSTR-2B for January 2026 showing the supplier invoices"),
            List.of("Bank statements evidencing payment to the suppliers", "Bank statement showing payments to the suppliers"));

    private V7DraftingPipelineService pipeline(IssueMatchingAgent matcher) {
        NewIssueDraftingAgent writer = mock(NewIssueDraftingAgent.class);
        when(writer.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            MatchedIssue i = inv.getArgument(0);
            return new NewIssueDraftingResult(i.issueNo(), "<p>Ground " + i.issueNo() + " is answered.</p>",
                    "<ol><li>Para 2: Denied. The provision is misapplied.</li></ol>", "<p>Submissions.</p>", List.of(), "note",
                    WRITER_DOCUMENTS.get(i.issueNo() - 1), "summary " + i.issueNo(), "test-model", NewIssueDraftingAgent.PROMPT_VERSION);
        });
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        assembly.registerInMemoryStageTemplate(new StageTemplate("rfd_08", 1, null, null, "active",
                Map.of("12", "<p>Sanction the refund of {{issue.amount_tax}} claimed in the application.</p>", "15", "<p>Checklist.</p>"),
                null, null, null));
        return new V7DraftingPipelineService(matcher, null, om, new TemplateFillService(null, null, om), assembly,
                new DraftCheckService(null), mock(PartialDraftingAgent.class), writer, mock(HighStakesDraftingAgent.class), null, null);
    }

    /** The runner's input: the header regex found no GSTIN, no state, no financial year. */
    private static DraftingInput input(String gstin, String state) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Noticedesk Technologies Private Limited", null, null,
                "Regular", gstin, state, "CGST Act", null, null, Map.of("document_type", "rfd_08"), Map.of(), TEXT, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static String section(DraftingPipelineResult r, int num) {
        return r.draft().sections().stream().filter(s -> s.num() == num).map(DraftSection::bodyHtml).findFirst().orElseThrow();
    }

    private static Optional<DraftCheckResult> flag(DraftingPipelineResult r, String flag) {
        return r.checkResults().stream().filter(c -> c.details() != null && flag.equals(c.details().get("flag"))).findFirst();
    }

    private DraftingPipelineResult run(DraftingInput input) {
        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(matching());
        return pipeline(matcher).runPipeline(input);
    }

    @Test
    void taxpayerGstinAndStateAreSetByCode() {
        DraftingPipelineResult r = run(input(null, null));

        String s01 = section(r, 1);
        String s03 = section(r, 3);
        assertTrue(s01.contains("for GSTIN " + PARTY), s01);
        assertTrue(s03.contains("(GSTIN: " + PARTY + ") is registered under the GST law in the State of Maharashtra."), s03);
        for (DraftSection s : r.draft().sections()) {
            if (s.num() == 13) continue;   // the partner note may count the other parties
            for (String other : List.of(SUPPLIER, "24CCCCC2222C1Z5", "29DDDDD3333D1Z5")) {
                assertFalse(s.bodyHtml().contains(other), "Section " + s.num() + " carries another party's GSTIN " + other);
            }
        }
        assertFalse(s03.contains("Centre Jurisdiction"));
        assertTrue(flag(r, "warn:missing_state").isEmpty(), "state derived: no missing-state flag");
        DraftCheckResult corrected = flag(r, V7DraftingPipelineService.FLAG_GSTIN_CORRECTED).orElseThrow();
        assertEquals(SUPPLIER, corrected.details().get("matcher"));
        assertEquals(PARTY, corrected.details().get("chosen"));
        assertEquals("party", corrected.details().get("source"));
    }

    @Test
    void anIntakeGstinOfASupplierIsReplacedAndAPlaceholderStateIsNeverPrinted() {
        DraftingPipelineResult r = run(input(SUPPLIER, "Centre Jurisdiction"));
        String s03 = section(r, 3);
        assertTrue(s03.contains("(GSTIN: " + PARTY + ") is registered under the GST law in the State of Maharashtra."), s03);
        assertEquals(SUPPLIER, flag(r, V7DraftingPipelineService.FLAG_GSTIN_CORRECTED).orElseThrow().details().get("intake"));
    }

    @Test
    void stateCannotBeDerivedWithoutAGstin() {
        V7DraftingPipelineService p = pipeline(mock(IssueMatchingAgent.class));
        MatchingResult mr = matching();
        DraftingInput in = new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Noticedesk Technologies Private Limited", null,
                null, "Regular", null, "Centre Jurisdiction", "CGST Act", null, null, Map.of("document_type", "rfd_08"), Map.of(),
                "Notice for rejection of refund. No GSTIN printed.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null);
        V7DraftingPipelineService.HeaderFacts hf = p.applyHeaderFacts(mr, in, in.noticeOcrExcerpt(), UUID.randomUUID(),
                new ArrayList<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
        assertNull(hf.input().registrationIdentifier(), "the matcher's GSTIN is not in the text: dropped");
        assertNull(hf.input().registrationStateName(), "Centre Jurisdiction is not a state");

        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        String s03 = assembly.assembleReply(hf.matching(), List.of(), hf.input(), "rfd_08").sections().stream()
                .filter(s -> s.num() == 3).findFirst().orElseThrow().bodyHtml();
        assertTrue(s03.contains("in the State of [[MISSING: state]]"), s03);
    }

    @Test
    void financialYearFromTheTaxPeriodFillsTheReplyHeader() {
        DraftingPipelineResult r = run(input(null, null));
        assertTrue(section(r, 2).contains("for Financial Year 2025-26."), section(r, 2));
        assertTrue(section(r, 3).contains("pertains to Financial Year 2025-26."), section(r, 3));
        assertFalse(section(r, 2).contains("[[MISSING: financial_year]]"));
    }

    @Test
    void statutoryParagraphIsNotedNotDenied() {
        String s05 = section(run(input(null, null)), 5);
        assertTrue(s05.contains("Para 2:</strong> " + ReplyAssemblyService.STATUTORY_PARA_REPLY), s05);
        assertFalse(s05.contains("Para 2:</strong> Denied"), s05);
        assertTrue(s05.contains("Para 3:</strong> Denied"), "an allegation is still denied: " + s05);
    }

    @Test
    void refundAmountsComeFromTheNotice() {
        DraftingPipelineResult r = run(input(null, null));
        String s01 = section(r, 1);
        assertTrue(s01.contains("Refund proposed to be rejected: Rs 1,78,63,659"), s01);
        assertFalse(s01.contains("Total Demand Amount"), s01);
        // the refund prayer is built by code: each ground with its amount, the refund as claimed, a hearing
        assertTrue(section(r, 12).contains("sanction the refund of Rs 1,78,63,659 as claimed"), section(r, 12));
        assertTrue(section(r, 12).contains("the ground at Issue #3 (Rs 28,63,659)"), section(r, 12));
        assertTrue(section(r, 14).contains("Refund Proposed to be Rejected:</strong> Rs 1,78,63,659"), section(r, 14));
    }

    @Test
    void clientLegalNameFromTheNoticeWhenTheClientRecordHasNone() {
        DraftingInput in = input(null, null);
        DraftingInput noName = new DraftingInput(in.matterId(), in.tone(), null, null, null, null, in.registrationType(), null, null,
                in.law(), null, null, in.notice(), Map.of(), TEXT, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        DraftingPipelineResult r = run(noName);
        for (DraftSection s : r.draft().sections()) {
            assertFalse(s.bodyHtml().contains("[[MISSING: client_legal_name]]"), "section " + s.num() + ": " + s.bodyHtml());
        }
        assertTrue(section(r, 2).contains("The taxpayer M/s Noticedesk Technologies Private Limited has received"), section(r, 2));
        assertFalse(section(r, 2).contains("Alpha Traders") || section(r, 3).contains("Beta Industries"), "never a supplier's name");
        assertTrue(flag(r, V7DraftingPipelineService.FLAG_CLIENT_NAME_FROM_NOTICE).isPresent());
    }

    @Test
    void refundPrayerAmountStaysMissingWhenTheNoticeNamesNone() {
        assertNull(ReplyAssemblyService.prayerRefundAmount(List.of(ground(1, 100, "1"), ground(2, 200, "2")),
                "Notice for rejection of refund; amounts in the annexure."));
        assertEquals("Rs 100", ReplyAssemblyService.prayerRefundAmount(List.of(ground(1, 100, "1")), "no amount in the head"));
    }

    @Test
    void documentsAreMergedInSections10And11And14() {
        DraftingPipelineResult r = run(input(null, null));
        String s10 = section(r, 10);
        int items = s10.split("<li>", -1).length - 1;
        assertEquals(4, items, s10);
        assertTrue(s10.contains("Refund application in FORM GST RFD-01 with all annexures"), s10);
        assertEquals(4, section(r, 11).split("Annexure A-", -1).length - 1, section(r, 11));
        String s14 = section(r, 14);
        assertEquals(4, s14.substring(s14.indexOf("Documents Required")).split("<li>", -1).length - 1, s14);
    }
}
