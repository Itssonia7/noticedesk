package com.noticedesk.api.service.layout;

import com.noticedesk.api.service.DocxExportService;
import com.noticedesk.api.testsupport.SyntheticS73Notice;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run at c82df8b on the synthetic Section 73 notice, letter and table cosmetics (Word and .txt): no blank line
 * between the Date line, the To block and the From block; the office line could break after "Division -"; the summary
 * table's "No." header wrapped and amounts wrapped.
 */
class LetterAndTableCosmeticsTest {

    private static ReplyLayout layout(String noticeText) {
        var input = SyntheticS73Notice.input();
        var withText = new com.noticedesk.api.agent.DraftingAgent.DraftingInput(input.matterId(), input.tone(), null,
                input.clientLegalName(), null, null, input.registrationType(), input.registrationIdentifier(),
                input.registrationStateName(), input.law(), input.financialYear(), null, input.notice(), input.rawExtractedJson(),
                noticeText, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        var issues = SyntheticS73Notice.issues();
        var svc = new com.noticedesk.api.service.matching.ReplyAssemblyService(null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        svc.registerInMemoryStageTemplate(new com.noticedesk.api.model.matching.StageTemplate("scn_73", 1, java.time.LocalDate.of(2017, 7, 1),
                java.time.LocalDate.of(2099, 12, 31), "draft", new java.util.TreeMap<>(java.util.Map.of("12", "<p>PRAYER: drop it.</p>",
                "15", "<p>FILING CHECKLIST: 1. Form GST DRC-06.</p>")), null, null, null));
        var r = svc.assembleReply(SyntheticS73Notice.matching(issues, SyntheticS73Notice.TAX_TOTAL), List.of(), withText, "scn_73",
                SyntheticS73Notice.aiFor(issues), null, List.of());
        return SyntheticS73Notice.layout(r);
    }

    private static final String TO_BLOCK = "To,\nThe Assistant Commissioner, CGST Division - Okhla\nCGST Delhi South Commissionerate\n";

    @Test
    void text_blankLinesBetweenDateToAndFrom_officeLineWhole() {
        String t = ReplyLayoutText.renderReply(layout(SyntheticS73Notice.text()));
        assertTrue(t.contains("Date: [date of filing]\n\n" + TO_BLOCK + "\nFrom,\n" + SyntheticS73Notice.CLIENT + "\n"), t.substring(0, 600));
        // a PDF text layer that breaks the heading after the dash gives the same To block
        String broken = SyntheticS73Notice.text().replace("CGST DIVISION - OKHLA", "CGST DIVISION -\nOKHLA");
        String t2 = ReplyLayoutText.renderReply(layout(broken));
        assertTrue(t2.contains(TO_BLOCK), t2.substring(0, 600));
        assertFalse(t2.contains("Division -\n"), t2.substring(0, 600));
        // an amount ending "/-" at a line end is not joined
        assertTrue(NoticeLetterHead.joinDashBreaks("OFFICE OF THE X\nRs. 1,000/-\nnext").contains("1,000/-\nnext"));
    }

    @Test
    void docx_letterAlignment_toBlock_andSummaryColumnWidths() throws Exception {
        byte[] bytes = new DocxExportService().renderReplyDocx(layout(SyntheticS73Notice.text()), false);
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> ps = d.getParagraphs();
            assertEquals(ParagraphAlignment.CENTER, ps.get(0).getAlignment(), "letterhead centred: " + ps.get(0).getText());
            assertTrue(ps.get(1).getText().startsWith("Date:"));
            assertEquals(ParagraphAlignment.RIGHT, ps.get(1).getAlignment(), "date right-aligned");
            assertEquals("", ps.get(2).getText(), "blank line after the date");
            assertEquals("To,", ps.get(3).getText());
            assertEquals("The Assistant Commissioner, CGST Division - Okhla", ps.get(4).getText().replace(' ', ' '));
            assertTrue(ps.get(4).getText().contains("Division - Okhla"), "no line break possible at the dash");
            assertEquals("CGST Delhi South Commissionerate", ps.get(5).getText());
            assertEquals("", ps.get(6).getText(), "blank line before the From block");
            assertEquals("From,", ps.get(7).getText());

            XWPFTable summary = d.getTables().stream().filter(t -> t.getRow(0).getCell(1).getText().equals("Ground")).findFirst().orElseThrow();
            int[] expected = {7, 35, 16, 42};
            for (XWPFTableRow row : summary.getRows()) {
                for (int c = 0; c < 4; c++) {
                    var w = row.getCell(c).getCTTc().getTcPr().getTcW();
                    assertEquals("pct", w.getType().toString(), "column " + c);
                    assertEquals(expected[c] * 50, new java.math.BigDecimal(w.getW().toString()).intValue(), "column " + c + " (fiftieths of a percent)");
                }
                assertTrue(row.getCell(2).getCTTc().getTcPr().isSetNoWrap(), "amount does not wrap");
                assertEquals(ParagraphAlignment.RIGHT, row.getCell(2).getParagraphs().get(0).getAlignment());
            }
            assertTrue(summary.getRow(0).getCell(0).getCTTc().getTcPr().isSetNoWrap(), "the No. header does not wrap");
            assertEquals("FIXED", summary.getCTTbl().getTblPr().getTblLayout().getType().toString().toUpperCase());
            var grid = summary.getCTTbl().getTblGrid().getGridColList();
            assertEquals(4, grid.size());
            int text = 11906 - 2 * 1440;
            for (int c = 0; c < 4; c++) assertEquals(text * expected[c] / 100, ((java.math.BigInteger) grid.get(c).getW()).intValue());
        }
    }
}
