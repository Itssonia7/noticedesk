package com.noticedesk.api.service.layout;

import com.noticedesk.api.service.DocxExportService;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b45) The filing layout as Word: A4, Times New Roman 12, justified body, Part headings with a thin rule under them,
 * hanging-indent numbering, tables with a light grey header row and right-aligned amounts, placeholders bold on yellow,
 * "Page X of Y" footer, and the internal review sheet after a page break.
 */
class ReplyDocxExportTest {

    private XWPFDocument docx(boolean review) throws Exception {
        byte[] bytes = new DocxExportService().renderReplyDocx(new ReplyLayoutTest().layout(), review);
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private static String allText(XWPFDocument d) {
        StringBuilder sb = new StringBuilder();
        for (IBodyElement e : d.getBodyElements()) {
            if (e instanceof XWPFParagraph p) sb.append(p.getText()).append('\n');
            if (e instanceof XWPFTable t) sb.append(t.getText()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void pageSetup_font_footer() throws Exception {
        try (XWPFDocument d = docx(true)) {
            var sect = d.getDocument().getBody().getSectPr();
            assertEquals("11906", sect.getPgSz().getW().toString());
            assertEquals("16838", sect.getPgSz().getH().toString());
            String footer = d.getFooterArray(0).getParagraphs().get(0).getCTP().xmlText();
            assertTrue(footer.contains("PAGE") && footer.contains("NUMPAGES"), footer);
            assertTrue(d.getFooterArray(0).getText().contains("Page") && d.getFooterArray(0).getText().contains("of"));
            for (XWPFParagraph p : d.getParagraphs()) {
                for (XWPFRun r : p.getRuns()) {
                    if (r.text().isBlank()) continue;
                    assertEquals("Times New Roman", r.getFontFamily(), r.text());
                    assertEquals(12.0, r.getFontSizeAsDouble(), r.text());
                }
            }
        }
    }

    @Test
    void headings_body_numbering_tables_placeholders() throws Exception {
        try (XWPFDocument d = docx(true)) {
            XWPFParagraph part = d.getParagraphs().stream().filter(p -> p.getText().equals("3. ISSUE-WISE REPLY")).findFirst().orElseThrow();
            assertEquals(Borders.SINGLE, part.getBorderBottom());
            assertTrue(part.getRuns().get(0).isBold());

            XWPFParagraph numbered = d.getParagraphs().stream().filter(p -> p.getText().startsWith("3.1\t")).findFirst().orElseThrow();
            assertEquals(ParagraphAlignment.BOTH, numbered.getAlignment());
            assertEquals(720, numbered.getIndentationHanging());

            XWPFTable summary = d.getTables().get(0);
            assertEquals("D9D9D9", summary.getRow(0).getCell(0).getColor());
            assertEquals(ParagraphAlignment.RIGHT, summary.getRow(1).getCell(2).getParagraphs().get(0).getAlignment());
            assertEquals("Rs 28,64,200", summary.getRow(1).getCell(2).getText());

            List<XWPFRun> highlighted = new ArrayList<>();
            for (XWPFParagraph p : d.getParagraphs()) {
                for (XWPFRun r : p.getRuns()) if (r.isHighlighted()) highlighted.add(r);
            }
            assertTrue(highlighted.stream().anyMatch(r -> r.text().equals("[date of receipt]") && r.isBold()), highlighted.toString());
            assertTrue(highlighted.stream().allMatch(XWPFRun::isBold));

            String text = allText(d);
            assertFalse(text.contains("[[") || text.contains("{{") || text.contains("") || text.contains(""), text);
            assertFalse(text.contains("Issue #"), text);
        }
    }

    @Test
    void reviewSheet_afterAPageBreak_andLeftOutForFiling() throws Exception {
        try (XWPFDocument d = docx(true)) {
            List<XWPFParagraph> ps = d.getParagraphs();
            int title = -1;
            for (int i = 0; i < ps.size(); i++) if (ps.get(i).getText().equals(ReplyLayout.REVIEW_TITLE)) title = i;
            assertTrue(title > 0);
            boolean breakBefore = false;
            for (int i = title - 1; i >= 0 && i >= title - 2; i--) {
                if (ps.get(i).getCTP().xmlText().contains("w:type=\"page\"")) breakBefore = true;
            }
            assertTrue(breakBefore, "page break right before the review sheet");
            assertTrue(allText(d).indexOf("PRAYER") < allText(d).indexOf(ReplyLayout.REVIEW_TITLE));
        }
        try (XWPFDocument filing = docx(false)) {
            assertFalse(allText(filing).contains(ReplyLayout.REVIEW_TITLE));
            assertTrue(allText(filing).contains("PRAYER"));
        }
    }
}
