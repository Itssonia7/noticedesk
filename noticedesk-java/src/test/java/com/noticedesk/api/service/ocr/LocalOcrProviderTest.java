package com.noticedesk.api.service.ocr;

import com.noticedesk.api.config.AppProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The local OCR provider on PDFs built in the test (synthetic text, fictional party): the text layer of a digital PDF
 * with page markers, table rows kept on one line, a scanned PDF through a fake tesseract runner, the refusal without
 * tesseract, and the factory wiring. No network.
 */
class LocalOcrProviderTest {

    /** A PDF with one line of text per entry of {@code pages}; a null page is left blank (a "scanned" page). */
    static byte[] pdf(List<List<String>> pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                if (lines == null) continue;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(font, 11);
                    float y = 800;
                    for (String line : lines) {
                        cs.beginText();
                        cs.newLineAtOffset(50, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 16;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Fake tesseract: present or absent; returns a fixed text per page image. */
    static LocalOcrProvider.TesseractRunner fakeTesseract(boolean present, List<Path> seen) {
        return new LocalOcrProvider.TesseractRunner() {
            @Override
            public boolean available() {
                return present;
            }

            @Override
            public String recognise(Path image) {
                seen.add(image);
                return "SCANNED PAGE TEXT " + seen.size() + "\nGSTIN: 27ZZZZZ9999Z1Z9\n";
            }
        };
    }

    @Test
    void digitalPdfTextLayerWithPageMarkersAndTableRows() throws Exception {
        byte[] bytes = pdf(List.of(
                List.of("FORM GST DRC-01", "To, M/s ZETA ALLOYS PRIVATE LIMITED", "GSTIN: 27ZZZZZ9999Z1Z9",
                        "Sr | Supplier | GSTIN | Taxable value", "1 | Alpha Traders | 24YYYYY8888Y1Z8 | 1,00,000"),
                List.of("2. The suppliers have not filed GSTR-3B for FY 2019-20.", "Section 73 of the CGST Act, 2017")));
        LocalOcrProvider p = new LocalOcrProvider(fakeTesseract(false, new ArrayList<>()));
        OcrResult r = p.process(bytes, "notice.pdf", "application/pdf");

        assertEquals("local", r.providerName());
        assertEquals(2, r.pageCount());
        assertEquals(2, GeminiOcrProvider.pageCount(r.text()), "the same === PAGE n === markers as the gemini provider");
        assertTrue(r.text().startsWith("=== PAGE 1 ===\nFORM GST DRC-01\n"), r.text());
        assertTrue(r.text().contains("\n=== PAGE 2 ===\n2. The suppliers have not filed GSTR-3B for FY 2019-20.\n"), r.text());
        assertTrue(r.text().contains("1 | Alpha Traders | 24YYYYY8888Y1Z8 | 1,00,000"), "a table row stays on one line\n" + r.text());
        assertTrue(r.text().contains("GSTIN: 27ZZZZZ9999Z1Z9"), r.text());
        // usage: no tokens, the engine named, so the runner logs the step at cost 0
        assertNotNull(r.usage());
        assertEquals("local", r.usage().provider());
        assertEquals(LocalOcrProvider.ENGINE_PDFBOX, r.usage().model());
        assertEquals(0, r.usage().inputTokensOrZero());
        assertEquals(0, r.usage().outputTokensOrZero());
    }

    @Test
    void scannedPdfIsReadPageByPageThroughTesseract() throws Exception {
        List<Path> seen = new ArrayList<>();
        byte[] bytes = pdf(Arrays.asList(null, null));
        LocalOcrProvider p = new LocalOcrProvider(fakeTesseract(true, seen));
        OcrResult r = p.process(bytes, "scan.pdf", "application/pdf");

        assertEquals(2, seen.size(), "one tesseract run per page");
        assertTrue(seen.get(0).getFileName().toString().endsWith(".png"));
        assertEquals(2, r.pageCount());
        assertEquals("=== PAGE 1 ===\nSCANNED PAGE TEXT 1\nGSTIN: 27ZZZZZ9999Z1Z9\n=== PAGE 2 ===\nSCANNED PAGE TEXT 2\nGSTIN: 27ZZZZZ9999Z1Z9\n", r.text());
        assertEquals(LocalOcrProvider.ENGINE_TESSERACT, r.usage().model());
        assertFalse(seen.get(0).toFile().exists(), "page images are deleted after the run");
    }

    @Test
    void scannedPdfWithoutTesseractIsRefusedNeverSentAnywhere() throws Exception {
        byte[] bytes = pdf(Arrays.asList(null, List.of("x")));   // two pages, at most one short text: scanned
        LocalOcrProvider p = new LocalOcrProvider(fakeTesseract(false, new ArrayList<>()));
        OcrException e = assertThrows(OcrException.class, () -> p.process(bytes, "scan.pdf", "application/pdf"));
        assertTrue(e.getMessage().contains(LocalOcrProvider.SCANNED_REFUSAL), e.getMessage());
        assertTrue(e.getMessage().contains("refusing to send raw bytes to a free-tier model"), e.getMessage());
        assertFalse(p.tesseractAvailable());
        // an image needs tesseract too
        OcrException img = assertThrows(OcrException.class, () -> p.process(new byte[]{1, 2, 3}, "scan.png", "image/png"));
        assertTrue(img.getMessage().contains(LocalOcrProvider.SCANNED_REFUSAL), img.getMessage());
    }

    @Test
    void aMostlyDigitalPdfKeepsItsTextLayerAndTesseractIsNotRun() throws Exception {
        List<Path> seen = new ArrayList<>();
        byte[] bytes = pdf(Arrays.asList(
                List.of("Page one has a proper text layer with enough words to count as digital text."),
                List.of("Page two also has a proper text layer with enough words to count as digital text."),
                null));   // a blank page (a separator sheet) in a text PDF
        OcrResult r = new LocalOcrProvider(fakeTesseract(true, seen)).process(bytes, "n.pdf", "application/pdf");
        assertEquals(0, seen.size(), "a digital PDF never goes through tesseract");
        assertEquals(3, r.pageCount());
        assertTrue(r.text().endsWith("=== PAGE 3 ===\n"), r.text());
        assertEquals(LocalOcrProvider.ENGINE_PDFBOX, r.usage().model());
    }

    @Test
    void emptyAndBrokenInputFail() {
        LocalOcrProvider p = new LocalOcrProvider(fakeTesseract(true, new ArrayList<>()));
        assertThrows(OcrException.class, () -> p.process(new byte[0], "x.pdf", "application/pdf"));
        OcrException e = assertThrows(OcrException.class, () -> p.process("not a pdf".getBytes(), "x.pdf", "application/pdf"));
        assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
    }

    @Test
    void factoryBuildsTheLocalProviderAndTheDefaultIsUnchanged() {
        AppProperties p = new AppProperties();
        p.getOcr().setProviderPrimary("local");
        assertEquals("local", new OcrFactory(p).getPrimaryProvider().getName());
        assertInstanceOf(LocalOcrProvider.class, new OcrFactory(p).getPrimaryProvider());
        p.getOcr().setProviderPrimary(" LOCAL ");
        assertEquals("local", new OcrFactory(p).getPrimaryProvider().getName());
        assertEquals("stub", new OcrFactory(new AppProperties()).getPrimaryProvider().getName());
    }

    @Test
    void helpers() {
        assertEquals("a\n\nb\n", LocalOcrProvider.normalise("a  \r\n\r\n\r\n\r\nb\n"));
        assertEquals(5, LocalOcrProvider.letterDigitCount("a1 b2, c."));
        assertEquals("=== PAGE 1 ===\nx\n=== PAGE 2 ===\n", LocalOcrProvider.join(List.of(" x ", "")));
    }
}
