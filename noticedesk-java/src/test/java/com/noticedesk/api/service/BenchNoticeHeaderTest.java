package com.noticedesk.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Offline: both bench header styles (dummy testbench and private set) and the placeholder cases. */
class BenchNoticeHeaderTest {

    /** Header of the committed dummy testbench (synthetic data; GSTIN is allow-listed). */
    private static final String DUMMY = """
            GOVERNMENT OF MAHARASHTRA
            OFFICE OF THE STATE TAX OFFICER

            Show Cause Notice under Section 73 of the CGST / MGST Act, 2017
            Reference No: SCN/MH/2023-24/606
            Date: 18-09-2023

            To,
            M/s Vantage Logistics Pvt Ltd
            GSTIN: 27IIIJJ1234I1Z9
            Financial Year: 2022-23

            1. WHEREAS, scrutiny of returns filed by you for FY 2021-22 was conducted under Section 61 of the CGST Act 2017.
            Due Date: 30-10-2023
            """;

    /** Header style of the private set (no real identifiers: every value is a form placeholder). */
    private static final String PRIVATE = """
            DIN: [____________________]
            Reference No.: [____________]      Dated: 24.05.2024

            To,
            M/s Noticedesk Technologies Private Limited
            GSTIN: [____________________]

            Subject: Show cause notice for FY 2019-20 - reg.

            Show cause notice under Section 73(1) of the CGST Act, 2017 read with Section 20 of the IGST Act
            """;

    @Test
    void dummyHeader_unchangedValues() {
        BenchNoticeHeader.Header h = BenchNoticeHeader.parse(DUMMY);
        assertEquals("SCN/MH/2023-24/606", h.reference());
        assertEquals("18-09-2023", h.date());
        assertEquals("M/s Vantage Logistics Pvt Ltd", h.clientName());
        assertEquals("27IIIJJ1234I1Z9", h.gstin());
        assertEquals("2022-23", h.financialYear());
        assertEquals("Section 73", h.section());
    }

    @Test
    void privateHeader_placeholdersAreNull_andFormatsNormalised() {
        BenchNoticeHeader.Header h = BenchNoticeHeader.parse(PRIVATE);
        assertNull(h.reference(), "bracket/underscore placeholder is not a value");
        assertNull(h.gstin(), "GSTIN placeholder is not a value");
        assertEquals("24-05-2024", h.date(), "Dated: dd.mm.yyyy -> dd-mm-yyyy");
        assertEquals("M/s Noticedesk Technologies Private Limited", h.clientName());
        assertEquals("2019-20", h.financialYear(), "first FY in the subject line");
        assertEquals("Section 73", h.section(), "sub-section in brackets stripped");
    }

    @Test
    void date_acceptsBothLabelsAndSeparators_butNotDueDate() {
        assertEquals("05-06-2023", BenchNoticeHeader.date("Reference No: X\nDate: 05-06-2023\n"));
        assertEquals("05-06-2023", BenchNoticeHeader.date("Reference No: X\nDated: 05.06.2023\n"));
        assertEquals("05-06-2023", BenchNoticeHeader.date("Reference No: X    Dated: 05.06.2023\n"));
        assertNull(BenchNoticeHeader.date("Reply by Due Date: 05-06-2023\n"));
        assertNull(BenchNoticeHeader.date("Dated: [__________]\n"));
        assertNull(BenchNoticeHeader.date(null));
    }

    @Test
    void reference_bothLabels_andPlaceholder() {
        assertEquals("SCN/DL/2021-22/740", BenchNoticeHeader.reference("Reference No: SCN/DL/2021-22/740\n"));
        assertEquals("SCN/DL/2021-22/740", BenchNoticeHeader.reference("Reference No.: SCN/DL/2021-22/740\n"));
        assertNull(BenchNoticeHeader.reference("Reference No.: [____________]      Dated: 24.05.2024"));
        assertNull(BenchNoticeHeader.reference("Reference No.: ______\n"));
        assertNull(BenchNoticeHeader.reference("no header at all"));
    }

    @Test
    void financialYear_variants() {
        assertEquals("2022-23", BenchNoticeHeader.financialYear("Financial Year: 2022-23"));
        assertEquals("2022-23", BenchNoticeHeader.financialYear("Financial Year: 2022-2023"));
        assertEquals("2019-20", BenchNoticeHeader.financialYear("Subject: SCN for FY 2019-20 and FY 2020-21"));
        assertEquals("2019-20", BenchNoticeHeader.financialYear("for F.Y. 2019-20"));
        assertEquals("2019-20", BenchNoticeHeader.financialYear("for F.Y.2019-2020"));
        // the labelled field wins over an earlier short form
        assertEquals("2022-23", BenchNoticeHeader.financialYear("FY 2018-19 scrutiny\nFinancial Year: 2022-23"));
        assertNull(BenchNoticeHeader.financialYear("Financial Year: [________]"));
        assertNull(BenchNoticeHeader.financialYear("no year here"));
    }

    @Test
    void section_variants_subSectionStripped() {
        assertEquals("Section 73", BenchNoticeHeader.section("Show cause notice under Section 73(1) of the CGST Act"));
        assertEquals("Section 74", BenchNoticeHeader.section("Notice under Section 74 of the CGST Act"));
        assertEquals("Section 74A", BenchNoticeHeader.section("Show Cause Notice under Section 74A of the CGST Act"));
        assertEquals("Section 171", BenchNoticeHeader.section("NOTICE UNDER SECTION 171 of the Act"));
        assertEquals("Section 73", BenchNoticeHeader.section("demand proposed under Section 73 of the CGST Act"));
        // the notice's own provision wins over an earlier bare "under Section"
        assertEquals("Section 74", BenchNoticeHeader.section("scrutiny under Section 61 was done.\nShow Cause Notice under Section 74 of the Act"));
        assertNull(BenchNoticeHeader.section("no provision"));
    }

    @Test
    void gstin_needsFifteenCharacters() {
        assertEquals("27IIIJJ1234I1Z9", BenchNoticeHeader.gstin("GSTIN: 27IIIJJ1234I1Z9\n"));
        assertNull(BenchNoticeHeader.gstin("GSTIN: [____________________]\n"));
        assertNull(BenchNoticeHeader.gstin("GSTIN: 27IIIJJ1234\n"));
    }

    @Test
    void placeholderRule() {
        assertTrue(BenchNoticeHeader.isPlaceholder(null));
        assertTrue(BenchNoticeHeader.isPlaceholder("   "));
        assertTrue(BenchNoticeHeader.isPlaceholder("[____________]"));
        assertTrue(BenchNoticeHeader.isPlaceholder("[ ___ ]"));
        assertTrue(BenchNoticeHeader.isPlaceholder("------"));
        assertFalse(BenchNoticeHeader.isPlaceholder("SCN/MH/2023-24/606"));
        assertFalse(BenchNoticeHeader.isPlaceholder("[A]"));
    }
}
