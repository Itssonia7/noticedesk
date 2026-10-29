package com.noticedesk.api.service.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Header facts read by code after matching: the taxpayer's GSTIN among several in the text, the financial year of a tax
 * period, the state of a GSTIN, the authority kind of the heading and the refund amount of an RFD-08 notice. Fictional
 * party; every GSTIN is a synthetic, allow-listed placeholder.
 */
class NoticeHeaderFactsTest {

    static final String PARTY = "27AAAAA0000A1Z5";
    static final String SUPPLIER_1 = "27BBBBB1111B1Z5";
    static final String SUPPLIER_2 = "24CCCCC2222C1Z5";
    static final String SUPPLIER_3 = "29DDDDD3333D1Z5";

    /** An RFD-08 refund show cause notice: the party once at the top, then supplier GSTINs in the body. */
    static final String RFD08 = """
            GOVERNMENT OF MAHARASHTRA
            OFFICE OF THE ASSISTANT COMMISSIONER OF STATE TAX
            FORM GST RFD-08
            [See rule 92(3)]
            Notice for rejection of application for refund

            In case of M/s Noticedesk Technologies Private Limited, GSTIN-27AAAAA0000A1Z5

            Refund Application ARN AA2701260000001 dated 10.02.2026 for Rs.1,78,63,659/- for the period JAN-2026
            under the CGST Act, 2017 and the MGST Act, 2017.

            1. The applicant has filed the above refund application on account of inverted duty structure.
            2. On verification, the inward supplies were found to be received from the following suppliers:
               (a) M/s Alpha Traders, GSTIN 27BBBBB1111B1Z5
               (b) M/s Beta Industries, GSTIN 24CCCCC2222C1Z5
               (c) M/s Gamma Agencies, GSTIN 29DDDDD3333D1Z5
            3. The suppliers listed above have not filed their returns for the period.
            """;

    // ------------------------------------------------------------------ GSTIN

    @Test
    void partyGstinIsChosenOverTheSupplierGstinsOfTheBody() {
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(RFD08);
        assertEquals(PARTY, tg.gstin());
        assertEquals(NoticeHeaderFacts.SOURCE_PARTY, tg.source());
        assertEquals(List.of(SUPPLIER_1, SUPPLIER_2, SUPPLIER_3), tg.otherParties(), "every other GSTIN is another party's");
    }

    @Test
    void matcherGstinIsOverriddenUnlessItIsTheCodeChoice() {
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(RFD08);
        assertTrue(tg.overrides(SUPPLIER_2), "a supplier's GSTIN from the matcher is overridden");
        assertFalse(tg.overrides(PARTY));
        assertFalse(tg.overrides(PARTY.toLowerCase()), "case does not matter");
        assertFalse(tg.overrides(null), "no matcher value: nothing to override");
    }

    @Test
    void partyGstinWinsEvenWhenASupplierGstinComesFirstInTheText() {
        String text = """
                Reference: verification of the supplier with GSTIN 27BBBBB1111B1Z5 for the period.
                To,
                M/s Noticedesk Technologies Private Limited
                GSTIN: 27AAAAA0000A1Z5
                """;
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(text);
        assertEquals(PARTY, tg.gstin());
        assertTrue(tg.fromParty());
        assertEquals(List.of(SUPPLIER_1), tg.otherParties());
    }

    @Test
    void withoutAPartyDesignationTheFirstGstinIsUsed() {
        String text = "Scrutiny of returns of 27AAAAA0000A1Z5 for the period; purchases from 27BBBBB1111B1Z5 were checked.";
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(text);
        assertEquals(PARTY, tg.gstin());
        assertEquals(NoticeHeaderFacts.SOURCE_FIRST, tg.source());
        assertFalse(tg.fromParty());
    }

    @Test
    void aGstinMoreThan120CharactersAfterTheDesignationIsNotThePartys() {
        String text = "In case of M/s Noticedesk Technologies Private Limited" + " ".repeat(150)
                + "supplier 27BBBBB1111B1Z5\nnoticee GSTIN 27AAAAA0000A1Z5";
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin(text);
        assertEquals(PARTY, tg.gstin(), "the M/s window holds no GSTIN; the next designation (noticee) has the party's");
    }

    @Test
    void noGstinInTheText() {
        NoticeHeaderFacts.TaxpayerGstin tg = NoticeHeaderFacts.taxpayerGstin("To, M/s Noticedesk Technologies Private Limited");
        assertNull(tg.gstin());
        assertEquals(NoticeHeaderFacts.SOURCE_NONE, tg.source());
        assertTrue(tg.overrides(SUPPLIER_1), "a matcher GSTIN the text does not support is dropped");
    }

    // ------------------------------------------------------------------ financial year

    @Test
    void financialYearFromTheTaxPeriodFormats() {
        assertEquals("2025-26", NoticeHeaderFacts.financialYearLabel("JAN-2026"));
        assertEquals("2025-26", NoticeHeaderFacts.financialYearLabel("January 2026"));
        assertEquals("2025-26", NoticeHeaderFacts.financialYearLabel("Jan-2026 to Mar-2026"));
        assertEquals("2025-26", NoticeHeaderFacts.financialYearLabel("FY 2025-26"));
        assertEquals("2025-26", NoticeHeaderFacts.financialYearLabel("2025-26"));
        assertEquals("2026-27", NoticeHeaderFacts.financialYearLabel("April 2026"));
        assertNull(NoticeHeaderFacts.financialYearLabel("as per records"));
        assertNull(NoticeHeaderFacts.financialYearLabel((String) null));
    }

    @Test
    void aPeriodSpanningTwoFinancialYearsNamesBoth() {
        assertEquals("FY 2025-26 and FY 2026-27", NoticeHeaderFacts.financialYearLabel("Jan-2026 to Jun-2026"));
        assertEquals("FY 2025-26 and FY 2026-27", NoticeHeaderFacts.financialYearLabel(List.of("JAN-2026", "May 2026")));
        assertEquals("FY 2025-26 and FY 2026-27", NoticeHeaderFacts.financialYearPhrase("FY 2025-26 and FY 2026-27"));
        assertEquals("Financial Year 2025-26", NoticeHeaderFacts.financialYearPhrase("2025-26"));
    }

    // ------------------------------------------------------------------ state and authority

    @Test
    void stateFromTheGstinStateCode() {
        assertEquals(Optional.of("Maharashtra"), NoticeHeaderFacts.stateForGstin(PARTY));
        assertEquals(Optional.of("Gujarat"), NoticeHeaderFacts.stateForGstin(SUPPLIER_2));
        assertEquals(Optional.of("Karnataka"), NoticeHeaderFacts.stateForGstin(SUPPLIER_3));
        assertEquals(Optional.of("Tamil Nadu"), NoticeHeaderFacts.stateForGstin("33EEEEE4444E1Z5"));
        assertEquals(Optional.empty(), NoticeHeaderFacts.stateForGstin("99AAAAA0000A1Z5"), "Centre Jurisdiction is not a state");
        assertEquals(Optional.empty(), NoticeHeaderFacts.stateForGstin(null));
        assertTrue(GstStateCodes.isPlaceholderName("Centre Jurisdiction"));
        assertTrue(GstStateCodes.isPlaceholderName("Other Territory"));
        assertFalse(GstStateCodes.isPlaceholderName("Maharashtra"));
    }

    @Test
    void everyListedStateCode() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("27", "Maharashtra"), Map.entry("24", "Gujarat"), Map.entry("07", "Delhi"), Map.entry("29", "Karnataka"),
                Map.entry("33", "Tamil Nadu"), Map.entry("36", "Telangana"), Map.entry("19", "West Bengal"),
                Map.entry("09", "Uttar Pradesh"), Map.entry("08", "Rajasthan"), Map.entry("06", "Haryana"), Map.entry("03", "Punjab"),
                Map.entry("23", "Madhya Pradesh"), Map.entry("32", "Kerala"), Map.entry("21", "Odisha"), Map.entry("10", "Bihar"),
                Map.entry("18", "Assam"), Map.entry("22", "Chhattisgarh"), Map.entry("20", "Jharkhand"), Map.entry("30", "Goa"),
                Map.entry("02", "Himachal Pradesh"), Map.entry("05", "Uttarakhand"), Map.entry("01", "Jammu and Kashmir"),
                Map.entry("04", "Chandigarh"), Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
                Map.entry("34", "Puducherry"), Map.entry("37", "Andhra Pradesh"), Map.entry("38", "Ladakh"));
        expected.forEach((code, name) -> assertEquals(Optional.of(name), GstStateCodes.nameForCode(code), code));
    }

    @Test
    void authorityKindFromTheHeading() {
        assertEquals(NoticeHeaderFacts.AUTHORITY_STATE, NoticeHeaderFacts.authorityKind(RFD08), "CGST Act in the body does not count");
        assertEquals(NoticeHeaderFacts.AUTHORITY_CENTRAL,
                NoticeHeaderFacts.authorityKind("OFFICE OF THE SUPERINTENDENT OF CENTRAL TAX\nRange II, Division III"));
        assertEquals(NoticeHeaderFacts.AUTHORITY_CENTRAL, NoticeHeaderFacts.authorityKind("CGST Range-IV, Pune Commissionerate"));
        assertNull(NoticeHeaderFacts.authorityKind("Notice under Section 73 of the CGST Act, 2017"));
    }

    // ------------------------------------------------------------------ refund

    @Test
    void refundAmountFollowsTheRefundApplication() {
        assertTrue(NoticeHeaderFacts.isRefundDocument(null, RFD08), "RFD-08 in the heading");
        assertTrue(NoticeHeaderFacts.isRefundDocument("rfd_08", ""));
        assertFalse(NoticeHeaderFacts.isRefundDocument("scn_73", "Show Cause Notice under Section 73"));
        assertEquals(new BigDecimal("17863659"), NoticeHeaderFacts.refundAmountFromText(RFD08));
        assertEquals(new BigDecimal("250000"), NoticeHeaderFacts.refundAmountFromText(
                "Your refund claim of Rs. 2,50,000/- filed on 01.02.2026 is proposed to be rejected."));
        assertNull(NoticeHeaderFacts.refundAmountFromText("Refund application ARN AA2701260000001 is under verification."),
                "an ARN is not an amount");
    }
}
