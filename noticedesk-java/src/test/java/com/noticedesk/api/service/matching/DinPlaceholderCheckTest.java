package com.noticedesk.api.service.matching;

import com.noticedesk.api.service.matching.DefectChecker.CodeDefect;
import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b40) The synthetic Section 74 notice has "DIN: [____________________]" and the code check reported
 * "din=ok (central-tax document dated 29.11.2024 carries a DIN)". A DIN / RFN is present only when a real identifier
 * (at least 15 letters / digits) follows the label.
 */
class DinPlaceholderCheckTest {

    private static final LocalDate DATE = LocalDate.of(2024, 11, 29);
    private static final String HEAD = "OFFICE OF THE JOINT COMMISSIONER OF CENTRAL TAX\nBENGALURU SOUTH COMMISSIONERATE\n\n";
    private static final String TAIL = "\nC.No. IV/16/118/2024-25 Adjn.\nDate: 29-11-2024\n\nSHOW CAUSE NOTICE UNDER SECTION 74(1)\n\n"
            + "(Officer)\nJoint Commissioner of Central Tax\n";

    private static CodeDefect check(String dinLine, String matcherDin) {
        return DefectChecker.din(HEAD + dinLine + TAIL, matcherDin, DATE);
    }

    @Test
    void liveNotice_blankBracketDin_isADefect_evenWhenTheMatcherReturnsADateOrFileNumber() {
        String text = SyntheticS74Notice.text();
        for (String matcherDin : new String[] {null, "[____________________]", "29-11-2024", "C.No. IV/16/118/2024-25 Adjn."}) {
            CodeDefect d = DefectChecker.din(text, matcherDin, DATE);
            assertEquals(DefectChecker.RESULT_DEFECT, d.result(), matcherDin + ": " + d.basis());
            assertTrue(d.basis().contains("blank or a placeholder"), d.basis());
        }
    }

    @Test
    void blankBrackets_underscores_nil_na_dashes_areNoDin() {
        for (String line : new String[] {"DIN: [____________________]", "DIN: ____________________", "DIN: NIL", "DIN: N/A",
                "DIN : ----------", "DIN: .................", "DIN:", "DIN No.: [ ]", "DIN: 29-11-2024"}) {
            CodeDefect d = check(line, null);
            assertEquals(DefectChecker.RESULT_DEFECT, d.result(), line + " -> " + d.basis());
        }
    }

    @Test
    void realLookingDin_isOk() {
        for (String line : new String[] {"DIN: 20241156ZZ000000AB12", "DIN - 2024 1156 ZZ 0000 00AB12", "DIN: CBIC-20241156ZZ0000AB12  Date: 29-11-2024"}) {
            CodeDefect d = check(line, null);
            assertEquals(DefectChecker.RESULT_OK, d.result(), line + " -> " + d.basis());
            assertTrue(d.basis().contains("carries a DIN"), d.basis());
        }
        // a label alone on its line, the value on the next
        assertEquals(DefectChecker.RESULT_OK, check("DIN:\n20241156ZZ000000AB12", null).result());
    }

    @Test
    void rfnOnlyDocument_isTreatedTheSameWay() {
        CodeDefect ok = check("RFN: ZD2911240012345", null);
        assertEquals(DefectChecker.RESULT_OK, ok.result(), ok.basis());
        assertTrue(ok.basis().contains("carries an RFN"), ok.basis());
        assertEquals(DefectChecker.RESULT_DEFECT, check("RFN: [__________]", null).result());
        assertEquals(DefectChecker.RESULT_DEFECT, check("RFN: NIL", null).result());
    }

    @Test
    void matcherDin_whenTheDocumentsDinLineIsBlank_countsOnlyWhenPrintedElsewhere() {
        // blank DIN line, but the identifier is printed elsewhere in the document (e.g. a table cell)
        String text = HEAD + "DIN: [______]\n| 20241156ZZ000000AB12 |" + TAIL;
        assertEquals(DefectChecker.RESULT_OK, DefectChecker.din(text, "20241156ZZ000000AB12", DATE).result());
        // blank DIN line and the matcher's value is nowhere in the document: not trusted
        assertEquals(DefectChecker.RESULT_DEFECT, check("DIN: [______]", "20241156ZZ000000AB12").result());
        // no DIN line at all (cut off by the text layer): a DIN-shaped matcher value is taken, a short one is not
        assertEquals(DefectChecker.RESULT_OK, DefectChecker.din(HEAD + TAIL, "20241156ZZ000000AB12", DATE).result());
        assertEquals(DefectChecker.RESULT_DEFECT, DefectChecker.din(HEAD + TAIL, "29-11-2024", DATE).result());
    }

    @Test
    void valueTest() {
        assertFalse(DefectChecker.looksLikeDinValue("[____________________]"));
        assertFalse(DefectChecker.looksLikeDinValue("NIL"));
        assertFalse(DefectChecker.looksLikeDinValue(null));
        assertFalse(DefectChecker.looksLikeDinValue("ABCDEFGHIJKLMNOPQRST"), "no digit");
        assertFalse(DefectChecker.looksLikeDinValue("SHOW CAUSE NOTICE UNDER SECTION 73"), "a heading on the next line");
        assertTrue(DefectChecker.looksLikeDinValue("20241156XX000000AB12"), "the anonymiser's DIN placeholder shape");
    }
}
