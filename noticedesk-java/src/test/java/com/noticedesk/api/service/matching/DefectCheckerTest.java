package com.noticedesk.api.service.matching;

import com.noticedesk.api.service.matching.DefectChecker.CodeDefect;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Procedural defects by code (live runs: the same time-barred Section 73 SCN and the same short-reply DRC-01A were
 * caught by the matcher in one run and missed in the next).
 */
class DefectCheckerTest {

    private final DefectChecker checker = new DefectChecker();

    private static String scn(String header, String section, String date, String fy, String footer) {
        return header + "\nShow Cause Notice under Section " + section + " of the Act\nReference No: SCN/1\nDate: " + date
                + "\n\nTo,\nM/s Test Traders\nGSTIN: TESTGSTIN\nFinancial Year: " + fy + "\n\n"
                + "1. WHEREAS returns were examined.\n2. Show cause why tax should not be demanded under Section " + section
                + "(1).\n3. Reply within 30 days.\n\n" + footer;
    }

    private static final String STATE_HEADER = "GOVERNMENT OF MAHARASHTRA\nOFFICE OF THE DEPUTY COMMISSIONER OF STATE TAX";
    private static final String CENTRAL_HEADER = "GOVERNMENT OF INDIA\nOFFICE OF THE SUPERINTENDENT OF CENTRAL TAX, RANGE-IV";
    private static final String SIGNED = "Digitally signed by RAJESH KUMAR\nSuperintendent, CGST Range-IV";
    private static final String STATE_SIGNED = "Digitally signed by RAJESH KUMAR\nDeputy Commissioner of State Tax";

    private CodeDefect run(String check, String text, String issueDate, String fy, String din, String type, String stage) {
        return checker.check(new DefectChecker.Input(text, issueDate, fy, null, din, type, stage, StageResolver.resolve(type, text)))
                .stream().filter(d -> d.check().equals(check)).findFirst().orElseThrow();
    }

    private CodeDefect run(String check, String text) {
        return run(check, text, null, null, null, "scn_73", null);
    }

    // ------------------------------------------------------------------ limitation_73

    @Test
    void limitation73_timeBarredScn_isADefect_onNdk105OnlyForFy2017_18And2018_19() {
        CodeDefect d = run(DefectChecker.LIMITATION_73, scn(STATE_HEADER, "73", "05.02.2024", "2018-19", SIGNED));
        assertEquals(DefectChecker.RESULT_DEFECT, d.result());
        assertEquals("NDK-105", d.cardIdReply());
        assertNull(d.cardIdAppeal());
        assertTrue(d.basis().contains("05.02.2024") && d.basis().contains("31.01.2024"), d.basis());

        // FY 2019-20: time-barred too, but NDK-105 is not written for that regime
        CodeDefect later = run(DefectChecker.LIMITATION_73, scn(STATE_HEADER, "73", "02/06/2024", "2019-20", SIGNED));
        assertEquals(DefectChecker.RESULT_DEFECT, later.result());
        assertNull(later.cardIdReply());
    }

    @Test
    void limitation73_inTime_isOk_andANonScnOrSection74IsNotApplicable() {
        CodeDefect d = run(DefectChecker.LIMITATION_73, scn(STATE_HEADER, "73", "15-10-2023", "2022-23", SIGNED));
        assertEquals(DefectChecker.RESULT_OK, d.result());
        assertTrue(d.isTestedOk());
        CodeDefect s74 = run(DefectChecker.LIMITATION_73, scn(STATE_HEADER, "74", "15-10-2023", "2018-19", SIGNED));
        assertEquals(DefectChecker.RESULT_OK, s74.result());
        assertFalse(s74.isTestedOk(), "not applicable never disputes the matcher");
    }

    @Test
    void limitation73_withoutADate_cannotBeTested() {
        String text = "Show Cause Notice under Section 73(1)\nFY 2019-20\nReply within 30 days.";
        CodeDefect d = run(DefectChecker.LIMITATION_73, text, null, null, null, "scn_73", null);
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, d.result());
        // an FY outside the table cannot be tested either
        CodeDefect fy24 = run(DefectChecker.LIMITATION_73, scn(STATE_HEADER, "73", "01.10.2028", "2024-25", SIGNED));
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, fy24.result());
    }

    @Test
    void limitation73_tableBoundaries_deadlineDayIsOk_nextDayIsADefect() {
        assertEquals(LocalDate.of(2023, 9, 30), DefectDeadlines.SCN_73_DEADLINE.get(2017));
        assertEquals(LocalDate.of(2027, 9, 30), DefectDeadlines.SCN_73_DEADLINE.get(2023));
        for (Map.Entry<Integer, LocalDate> e : DefectDeadlines.SCN_73_DEADLINE.entrySet()) {
            LocalDate deadline = e.getValue();
            assertEquals(DefectChecker.RESULT_OK,
                    DefectChecker.limitation73(StageResolver.DocumentKind.SCN, "73", deadline, e.getKey()).result(), "FY " + e.getKey());
            assertEquals(DefectChecker.RESULT_DEFECT,
                    DefectChecker.limitation73(StageResolver.DocumentKind.SCN, "73", deadline.plusDays(1), e.getKey()).result(), "FY " + e.getKey());
        }
        assertEquals(7, DefectDeadlines.SCN_73_DEADLINE.size());
    }

    // ------------------------------------------------------------------ premature_74

    @Test
    void premature74_scnBeforeTheGstr9DueDate_isADefectOnNdk104() {
        CodeDefect d = run(DefectChecker.PREMATURE_74, scn(STATE_HEADER, "74", "30.12.2023", "2022-23", SIGNED), null, null, null,
                "scn_74", "scn_74");
        assertEquals(DefectChecker.RESULT_DEFECT, d.result());
        assertEquals("NDK-104", d.cardIdReply());
        assertTrue(d.basis().contains("31.12.2023"), d.basis());
    }

    @Test
    void premature74_onOrAfterTheDueDate_isOk_andBoundariesHold() {
        CodeDefect d = run(DefectChecker.PREMATURE_74, scn(STATE_HEADER, "74", "31.12.2023", "2022-23", SIGNED), null, null, null,
                "scn_74", "scn_74");
        assertEquals(DefectChecker.RESULT_OK, d.result());
        for (Map.Entry<Integer, LocalDate> e : DefectDeadlines.GSTR9_DUE_DATE.entrySet()) {
            assertEquals(DefectChecker.RESULT_DEFECT, DefectChecker.premature74(StageResolver.DocumentKind.SCN, "74",
                    e.getValue().minusDays(1), e.getKey()).result());
            assertEquals(DefectChecker.RESULT_OK, DefectChecker.premature74(StageResolver.DocumentKind.SCN, "74",
                    e.getValue(), e.getKey()).result());
        }
        // a Section 73 SCN: not applicable
        assertFalse(run(DefectChecker.PREMATURE_74, scn(STATE_HEADER, "73", "01.01.2020", "2018-19", SIGNED)).isTestedOk());
    }

    @Test
    void premature74_withoutAFinancialYear_cannotBeTested() {
        String text = "Show Cause Notice under Section 74(1)\nDate: 01.01.2023\nReply within 30 days.";
        CodeDefect d = run(DefectChecker.PREMATURE_74, text, null, null, null, "scn_74", null);
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, d.result());
    }

    // ------------------------------------------------------------------ din

    @Test
    void din_placeholderOrNotGeneratedOrNoLine_onACentralTaxDocument_isADefect() {
        for (String dinLine : List.of("DIN: [____]", "DIN: not generated", "DIN: XXXXXXXX", "DIN:")) {
            String text = scn(CENTRAL_HEADER + "\n" + dinLine, "73", "02.05.2024", "2019-20", SIGNED);
            CodeDefect d = run(DefectChecker.DIN, text);
            assertEquals(DefectChecker.RESULT_DEFECT, d.result(), dinLine);
            assertEquals("NDK-141", d.cardIdReply());
            assertEquals("NDK-142", d.cardIdAppeal());
        }
        CodeDefect noLine = run(DefectChecker.DIN, scn(CENTRAL_HEADER, "73", "02.05.2024", "2019-20", SIGNED));
        assertEquals(DefectChecker.RESULT_DEFECT, noLine.result());
        assertTrue(noLine.basis().contains("no DIN line"), noLine.basis());
        // an officer designation in the signature block makes a document central too
        String officer = scn("OFFICE OF THE ASSISTANT COMMISSIONER\nDIN: [____]", "73", "02.05.2024", "2019-20",
                "Signature: Sd/-\nAssistant Commissioner, Central Goods and Services Tax, Division-II");
        assertEquals(DefectChecker.RESULT_DEFECT, run(DefectChecker.DIN, officer).result());
    }

    @Test
    void din_realValue_stateDocument_orAnOldDocument_isOk() {
        String withDin = scn(CENTRAL_HEADER + "\nDIN: 20240556YC000000AB12", "73", "02.05.2024", "2019-20", SIGNED);
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.DIN, withDin).result());
        // the matcher read the DIN although the text line is cut off
        assertEquals(DefectChecker.RESULT_OK,
                run(DefectChecker.DIN, scn(CENTRAL_HEADER, "73", "02.05.2024", "2019-20", SIGNED), null, null, "20240556YC000000AB12", "scn_73", null).result());
        // a State Tax notice that names the CGST Act is not a central-tax document
        CodeDefect state = run(DefectChecker.DIN, scn(STATE_HEADER + "\nUnder the CGST / MGST Act, 2017\nDIN: [____]", "73", "02.05.2024", "2019-20", STATE_SIGNED));
        assertEquals(DefectChecker.RESULT_OK, state.result());
        assertFalse(state.isTestedOk());
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.DIN, scn(CENTRAL_HEADER, "73", "01.11.2019", "2017-18", SIGNED)).result());
    }

    @Test
    void din_centralSlashStateHeaderWithoutACentralDesignation_cannotBeTested() {
        String text = scn("OFFICE OF THE COMMISSIONER OF CENTRAL / STATE TAX\nDIN: [____]", "73", "02.05.2024", "2019-20",
                "Sd/-\nProper Officer");
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, run(DefectChecker.DIN, text).result());
    }

    // ------------------------------------------------------------------ signature

    @Test
    void signature_blankFieldsOrNoSignatureNeeded_isADefect() {
        CodeDefect blank = run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20", "Signature: Name: Designation:"));
        assertEquals(DefectChecker.RESULT_DEFECT, blank.result());
        assertEquals("NDK-143", blank.cardIdReply());
        assertEquals("NDK-144", blank.cardIdAppeal());
        CodeDefect line = run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20", "_______________\n(Proper Officer)"));
        assertEquals(DefectChecker.RESULT_DEFECT, line.result());
        CodeDefect system = run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20",
                "This is a system generated notice and does not require signature."));
        assertEquals(DefectChecker.RESULT_DEFECT, system.result());
    }

    @Test
    void signature_digitallySignedOrSd_isOk() {
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20",
                "Digitally signed by RAJESH KUMAR\nDate: 02.05.2024\nSignature: Name: Designation:")).result());
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20",
                "Sd/-\n(R. Kumar)\nDeputy Commissioner")).result());
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.SIGNATURE, scn(STATE_HEADER, "73", "02.05.2024", "2019-20",
                "Deputy Commissioner of State Tax")).result(), "no blank block: ok");
    }

    @Test
    void signature_withoutText_cannotBeTested() {
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, run(DefectChecker.SIGNATURE, "").result());
    }

    // ------------------------------------------------------------------ drc01a_reply_time

    private static String drc01a(String date, String partB) {
        return "FORM GST DRC-01A\nPart A\nIntimation of tax ascertained as being payable under Section 73(5)\nDate: " + date
                + "\nFinancial Year 2019-20\n1. Tax of Rs 1,00,000 is ascertained.\n" + partB + "\nSd/-\nSuperintendent";
    }

    @Test
    void drc01a_replyDateFewerThan15DaysAway_isADefectOnNdk148() {
        CodeDefect d = run(DefectChecker.DRC01A_REPLY_TIME, drc01a("02.05.2024", "Submit your reply in Part B on or before 06.05.2024."),
                null, null, null, "drc_01a", null);
        assertEquals(DefectChecker.RESULT_DEFECT, d.result());
        assertEquals("NDK-148", d.cardIdReply());
        assertTrue(d.basis().contains("02.05.2024") && d.basis().contains("06.05.2024") && d.basis().contains("4 days"), d.basis());
        CodeDefect within = run(DefectChecker.DRC01A_REPLY_TIME, drc01a("02.05.2024", "Reply within 7 days."), null, null, null, "drc_01a", null);
        assertEquals(DefectChecker.RESULT_DEFECT, within.result());
    }

    @Test
    void drc01a_enoughTime_isOk_andTheMinimumIsConfigurable() {
        String text = drc01a("02.05.2024", "Submit your reply in Part B on or before 01.06.2024.");
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.DRC01A_REPLY_TIME, text, null, null, null, "drc_01a", null).result());
        // exactly 15 days is enough; 14 is not
        assertEquals(DefectChecker.RESULT_OK, run(DefectChecker.DRC01A_REPLY_TIME,
                drc01a("02.05.2024", "Reply by Part B on or before 17.05.2024."), null, null, null, "drc_01a", null).result());
        assertEquals(DefectChecker.RESULT_DEFECT, run(DefectChecker.DRC01A_REPLY_TIME,
                drc01a("02.05.2024", "Reply by Part B on or before 16.05.2024."), null, null, null, "drc_01a", null).result());
        DefectChecker strict = new DefectChecker(45);
        CodeDefect d = strict.check(new DefectChecker.Input(text, null, null, null, null, "drc_01a", null, ResponseStage.REPLY))
                .stream().filter(c -> c.check().equals(DefectChecker.DRC01A_REPLY_TIME)).findFirst().orElseThrow();
        assertEquals(DefectChecker.RESULT_DEFECT, d.result());
        // not a DRC-01A: not applicable
        assertFalse(run(DefectChecker.DRC01A_REPLY_TIME, scn(STATE_HEADER, "73", "02.05.2024", "2019-20", SIGNED)).isTestedOk());
    }

    @Test
    void drc01a_withoutAReplyDate_cannotBeTested() {
        CodeDefect d = run(DefectChecker.DRC01A_REPLY_TIME, drc01a("02.05.2024", "Pay the amount."), null, null, null, "drc_01a", null);
        assertEquals(DefectChecker.RESULT_CANNOT_TEST, d.result());
    }

    // ------------------------------------------------------------------ reading

    @Test
    void fieldsComeFromTheMatcherFirstThenFromTheText() {
        assertEquals(LocalDate.of(2024, 5, 2), DefectChecker.documentDate("02-05-2024", "Date: 01.01.2020"));
        assertEquals(LocalDate.of(2024, 5, 2), DefectChecker.documentDate("2024-05-02", null));
        assertEquals(LocalDate.of(2020, 1, 1), DefectChecker.documentDate(null, "Ref 1\nDated: 01/01/2020"));
        assertNull(DefectChecker.parseDate("31.02.2024"), "invalid date");
        assertEquals(2019, DefectChecker.financialYearStart("2019-20", "FY 2022-23"));
        assertEquals(2019, DefectChecker.financialYearStart(null, "for the Financial Year 2019-2020"));
        assertEquals(2022, DefectChecker.financialYearStart(null, "F.Y. 2022-23"));
        assertNull(DefectChecker.financialYearStart(null, "2019-25"));
        assertEquals("73", DefectChecker.scnSection(null, "demand under Section 73(1) of the Act"));
        assertEquals("74", DefectChecker.scnSection(null, "Section 74(1)"));
        assertEquals("74A", DefectChecker.scnSection(null, "notice under Section 74A"));
        assertEquals("74", DefectChecker.scnSection("scn_74", "Section 73(1)"), "the stage wins over the text");
    }

    @Test
    void everyBasisIsAtMost20Words() {
        CodeDefect d = new CodeDefect("x", "ok", "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen"
                + " sixteen seventeen eighteen nineteen twenty twentyone twentytwo", null, null);
        assertEquals(20, d.basis().split("\\s+").length);
        for (CodeDefect c : checker.check(new DefectChecker.Input(scn(CENTRAL_HEADER + "\nDIN: [____]", "73", "02.06.2024", "2019-20",
                "Signature: Name: Designation:"), null, null, null, null, "scn_73", "scn_73", ResponseStage.REPLY))) {
            assertTrue(c.basis().split("\\s+").length <= 20, c.basis());
        }
    }
}
