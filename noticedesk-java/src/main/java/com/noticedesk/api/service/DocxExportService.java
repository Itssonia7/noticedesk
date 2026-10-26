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
                    XWPFRun lr = lp.createRun();
                    lr.setText("• " + itemText);
                    lr.setFontFamily("Times New Roman");
                    lr.setFontSize(11);
                }
                continue;
            }
            XWPFRun run = p.createRun();
            run.setText(text);
            run.setBold(el.tagName().matches("h[1-6]"));   // issue headings and sub-headings
            run.setFontFamily("Times New Roman");
            run.setFontSize(11);
        }
    }

    private String nvl(String s) {
        return s != null ? s : "—";
    }
}
