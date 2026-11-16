package com.noticedesk.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocxExportService {

    private static final Set<String> FILING_EXCLUDED_SECTIONS = Set.of();
    private static final Set<Integer> CLIENT_EXCLUDED_SECTIONS = Set.of(13, 14);
    // filing: exclude none; client: exclude 13-14; internal: include all

    public record CoverSheetData(
            String firmName,
            String clientLegalName,
            String clientPan,
            String registrationLabel,
            String registrationIdentifier,
            String noticeType,
            String fyOrAy,
            String authority,
            String dueDate
    ) {}

    /**
     * Render the draft as a DOCX blob.
     *
     * @param mode       "filing" | "client" | "internal"
     * @param cover      cover sheet metadata
     * @param sections   list of section maps with keys: num, title, body_html
     * @param internalNote partner-only note appended in internal mode
     */
    public byte[] renderDraftDocx(
            String mode,
            CoverSheetData cover,
            List<Map<String, Object>> sections,
            String internalNote
    ) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // ── Cover sheet ──────────────────────────────────────────────
            addCenteredParagraph(doc, cover.firmName(), 18, true);
            addCenteredParagraph(doc, "Reply to " + nvl(cover.noticeType()), 14, false);
            addCenteredParagraph(doc, cover.clientLegalName() + " · PAN: " + cover.clientPan(), 11, false);
            addCenteredParagraph(doc,
                    cover.registrationLabel() + ": " + nvl(cover.registrationIdentifier()), 11, false);
            addCenteredParagraph(doc, "Period: " + nvl(cover.fyOrAy()), 11, false);
            if (cover.authority() != null && !cover.authority().isBlank()) {
                addCenteredParagraph(doc, "Authority: " + cover.authority(), 11, false);
            }
            if (cover.dueDate() != null && !cover.dueDate().isBlank()) {
                addCenteredParagraph(doc, "Due: " + cover.dueDate(), 11, false);
            }
            doc.createParagraph(); // spacer

            // ── Sections ─────────────────────────────────────────────────
            for (Map<String, Object> section : sections) {
                int num = section.get("num") instanceof Number n ? n.intValue() : 0;
                String title = String.valueOf(section.getOrDefault("title", ""));
                String bodyHtml = String.valueOf(section.getOrDefault("body_html", ""));

                if (shouldExcludeSection(mode, num)) continue;

                // Section heading
                XWPFParagraph heading = doc.createParagraph();
                heading.setStyle("Heading2");
                XWPFRun hr = heading.createRun();
                hr.setText(num + ". " + title);
                hr.setBold(true);
                hr.setFontSize(12);

                // Body — strip HTML and write paragraphs
                renderHtmlParagraphs(doc, bodyHtml);
                doc.createParagraph(); // spacer between sections
            }

            // ── Internal partner note (internal mode only) ────────────────
            if ("internal".equals(mode) && internalNote != null && !internalNote.isBlank()) {
                XWPFParagraph noteHeader = doc.createParagraph();
                XWPFRun nhr = noteHeader.createRun();
                nhr.setText("── Internal Partner Note ──");
                nhr.setBold(true);
                nhr.setFontSize(11);
                renderHtmlParagraphs(doc, internalNote);
            }

            doc.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("docx export failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ filing layout (b45)

    /** A4 in twentieths of a point, 2.54 cm margins. */
    static final int A4_WIDTH = 11906;
    static final int A4_HEIGHT = 16838;
    static final int MARGIN = 1440;
    static final String FONT = "Times New Roman";
    static final int FONT_SIZE = 12;
    static final String HEADER_FILL = "D9D9D9";

    /**
     * The partner-approved filing layout as Word: A4, Times New Roman 12, justified body, Part headings with a thin rule
     * under them, numbered paragraphs with a hanging indent, tables with a light grey header row (amount columns right
     * aligned), every placeholder bold on yellow, "Page X of Y" in the footer. With {@code includeReview} the internal
     * review sheet follows the reply after a page break.
     */
    public byte[] renderReplyDocx(com.noticedesk.api.service.layout.ReplyLayout layout, boolean includeReview) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pageSetup(doc);
            footer(doc);
            writeBlocks(doc, layout.reply());
            if (includeReview) writeBlocks(doc, layout.review());
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("docx export failed: " + e.getMessage(), e);
        }
    }

    private static void pageSetup(XWPFDocument doc) {
        var sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        var size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(java.math.BigInteger.valueOf(A4_WIDTH));
        size.setH(java.math.BigInteger.valueOf(A4_HEIGHT));
        var mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        java.math.BigInteger m = java.math.BigInteger.valueOf(MARGIN);
        mar.setTop(m);
        mar.setBottom(m);
        mar.setLeft(m);
        mar.setRight(m);
    }

    /** "Page X of Y" centred in the footer (PAGE and NUMPAGES fields). */
    private static void footer(XWPFDocument doc) {
        XWPFFooter footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
        XWPFParagraph p = footer.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        styled(p.createRun(), "Page ", false, false);
        field(p, "PAGE", "1");
        styled(p.createRun(), " of ", false, false);
        field(p, "NUMPAGES", "1");
    }

    private static void field(XWPFParagraph p, String instr, String shown) {
        var f = p.getCTP().addNewFldSimple();
        f.setInstr(instr + " \\* MERGEFORMAT");
        var r = f.addNewR();
        var rpr = r.addNewRPr();
        rpr.addNewRFonts().setAscii(FONT);
        rpr.getRFontsArray(0).setHAnsi(FONT);
        rpr.addNewSz().setVal(java.math.BigInteger.valueOf(FONT_SIZE * 2L));
        r.addNewT().setStringValue(shown);
    }

    private static void writeBlocks(XWPFDocument doc, java.util.List<com.noticedesk.api.service.layout.ReplyLayout.Block> blocks) {
        for (var b : blocks) {
            if (b instanceof com.noticedesk.api.service.layout.ReplyLayout.PageBreak) {
                XWPFParagraph p = doc.createParagraph();
                p.createRun().addBreak(BreakType.PAGE);
            } else if (b instanceof com.noticedesk.api.service.layout.ReplyLayout.Para para) {
                writePara(doc, para);
            } else if (b instanceof com.noticedesk.api.service.layout.ReplyLayout.Table t) {
                writeTable(doc, t);
            }
        }
    }

    private static void writePara(XWPFDocument doc, com.noticedesk.api.service.layout.ReplyLayout.Para para) {
        XWPFParagraph p = doc.createParagraph();
        boolean bold = false;
        boolean italic = false;
        p.setSpacingAfter(120);
        switch (para.style()) {
            case LETTER, SIGNATURE -> {
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setSpacingAfter(0);
            }
            case SUBJECT -> {
                p.setAlignment(ParagraphAlignment.BOTH);
                p.setSpacingBefore(240);
                p.setSpacingAfter(240);
                bold = true;
            }
            case TITLE -> {
                p.setAlignment(ParagraphAlignment.CENTER);
                p.setSpacingAfter(240);
                bold = true;
            }
            case PART -> {
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setBorderBottom(Borders.SINGLE);
                p.setSpacingBefore(240);
                p.setKeepNext(true);
                bold = true;
            }
            case HEADING -> {
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setSpacingBefore(180);
                p.setKeepNext(true);
                bold = true;
            }
            case SUBHEADING -> {
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setKeepNext(true);
                bold = true;
                italic = true;
            }
            default -> p.setAlignment(ParagraphAlignment.BOTH);
        }
        String text = para.text() != null ? para.text() : "";
        if (para.number() != null) {
            // hanging indent: the number in the margin, the text aligned after it
            p.setIndentationLeft(720);
            p.setIndentationHanging(720);
            styled(p.createRun(), para.number() + "\t", bold, italic);
        }
        highlightedRuns(p, text, bold, italic);
    }

    /** Text as runs; a placeholder between the layout's highlight markers is bold on yellow, without the markers. */
    static void highlightedRuns(XWPFParagraph p, String text, boolean bold, boolean italic) {
        char start = com.noticedesk.api.service.layout.ReplyLayout.HL_START;
        char end = com.noticedesk.api.service.layout.ReplyLayout.HL_END;
        int i = 0;
        while (i < text.length()) {
            int s = text.indexOf(start, i);
            if (s < 0) {
                styled(p.createRun(), text.substring(i), bold, italic);
                break;
            }
            if (s > i) styled(p.createRun(), text.substring(i, s), bold, italic);
            int e = text.indexOf(end, s);
            if (e < 0) e = text.length();
            XWPFRun r = p.createRun();
            styled(r, text.substring(s + 1, e), true, italic);
            r.setTextHighlightColor("yellow");
            i = e + 1;
        }
    }

    private static void styled(XWPFRun r, String text, boolean bold, boolean italic) {
        String[] parts = text.split("\t", -1);
        for (int k = 0; k < parts.length; k++) {
            if (k > 0) r.addTab();
            r.setText(parts[k], k);
        }
        r.setBold(bold);
        r.setItalic(italic);
        r.setFontFamily(FONT);
        r.setFontSize(FONT_SIZE);
    }

    private static void writeTable(XWPFDocument doc, com.noticedesk.api.service.layout.ReplyLayout.Table t) {
        int cols = t.header().size();
        XWPFTable table = doc.createTable(t.rows().size() + 1, cols);
        table.setWidth("100%");
        for (int c = 0; c < cols; c++) {
            XWPFTableCell cell = table.getRow(0).getCell(c);
            cell.setColor(HEADER_FILL);
            cellText(cell, t.header().get(c), true, t.rightAligned().contains(c));
        }
        for (int r = 0; r < t.rows().size(); r++) {
            java.util.List<String> row = t.rows().get(r);
            for (int c = 0; c < cols; c++) {
                cellText(table.getRow(r + 1).getCell(c), c < row.size() && row.get(c) != null ? row.get(c) : "", false,
                        t.rightAligned().contains(c));
            }
        }
        int[] widths = columnWidths(t.header());
        for (XWPFTableRow row : table.getRows()) {
            for (int c = 0; c < cols && c < widths.length; c++) row.getCell(c).setWidth(widths[c] + "%");
        }
        table.getRow(0).setRepeatHeader(true);
        doc.createParagraph().setSpacingAfter(0);   // space after the table
    }

    /** Column widths in percent: a narrow number / reference column, a wide text column. */
    static int[] columnWidths(java.util.List<String> header) {
        int cols = header.size();
        if (cols == 4) return new int[]{8, 40, 17, 35};
        if (cols == 2) {
            String first = header.get(0).toLowerCase(java.util.Locale.ROOT);
            return first.startsWith("para") || first.startsWith("annexure") ? new int[]{22, 78} : new int[]{55, 45};
        }
        int[] w = new int[cols];
        java.util.Arrays.fill(w, 100 / Math.max(1, cols));
        return w;
    }

    private static void cellText(XWPFTableCell cell, String text, boolean bold, boolean right) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.setAlignment(right ? ParagraphAlignment.RIGHT : ParagraphAlignment.LEFT);
        p.setSpacingAfter(0);
        highlightedRuns(p, text, bold, false);
    }

    private boolean shouldExcludeSection(String mode, int num) {
        if ("client".equals(mode) && CLIENT_EXCLUDED_SECTIONS.contains(num)) return true;
        return false;
    }

    private void addCenteredParagraph(XWPFDocument doc, String text, int fontSize, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();
        run.setText(text != null ? text : "");
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontFamily("Times New Roman");
    }

    private void renderHtmlParagraphs(XWPFDocument doc, String html) {
        if (html == null || html.isBlank()) return;
        // Parse HTML and extract text blocks, preserving paragraph structure
        org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(html);
        for (Element el : parsed.body().children()) {
            String text = el.text().strip();
            if (text.isBlank()) continue;
            XWPFParagraph p = doc.createParagraph();
            // Handle list items
            if (el.tagName().equals("ul") || el.tagName().equals("ol")) {
                for (Element li : el.select("li")) {
                    // a nested list's items are their own lines: the item's text without them, indented by depth
                    Element own = li.clone();
                    own.select("ul, ol").remove();
                    String itemText = own.text().strip();
                    if (itemText.isBlank()) continue;
                    XWPFParagraph lp = doc.createParagraph();
                    long depth = li.parents().stream().filter(a -> a.tagName().equals("ul") || a.tagName().equals("ol")).count();
                    lp.setIndentationLeft((int) (720 * Math.max(1, depth)));
                    addRuns(lp, "• " + itemText, false);
                }
                continue;
            }
            addRuns(p, text, el.tagName().matches("h[1-6]"));   // issue headings and sub-headings are bold
        }
    }

    /**
     * A draft marker the partner must resolve before filing: [[MISSING: x]], [[CONFIRM: fact]], [[PENDING_AI: ...]]
     * or [[PARTNER: ...]]. Rendered in bold so it stands out in the Word document.
     */
    static final Pattern MARKER = Pattern.compile("\\[\\[\\s*(?:MISSING|CONFIRM|PENDING_AI|PARTNER)\\b[^\\]]*\\]\\]");

    /** The text as runs: plain text in the paragraph's weight, every marker ({@link #MARKER}) in bold. */
    static void addRuns(XWPFParagraph p, String text, boolean bold) {
        Matcher m = MARKER.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addRun(p, text.substring(last, m.start()), bold);
            addRun(p, m.group(), true);
            last = m.end();
        }
        if (last < text.length()) addRun(p, text.substring(last), bold);
    }

    private static void addRun(XWPFParagraph p, String text, boolean bold) {
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontFamily("Times New Roman");
        run.setFontSize(11);
    }

    private String nvl(String s) {
        return s != null ? s : "—";
    }
}
