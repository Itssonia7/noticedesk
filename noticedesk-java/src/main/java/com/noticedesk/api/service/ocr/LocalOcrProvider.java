package com.noticedesk.api.service.ocr;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * OCR that never leaves the machine ("local"): the text layer of a digital PDF is read with Apache PDFBox; a scanned
 * PDF (no or a near-empty text layer) and an image are run through the system {@code tesseract} command when it is on
 * the PATH. Nothing is sent to any network service, so the document can be anonymised before any model sees it.
 *
 * <p>Pages are separated by {@code === PAGE n ===} lines exactly as {@link GeminiOcrProvider} does, so downstream code
 * (page count, header facts, the anonymiser) is unchanged. Text is read in reading order ({@code sortByPosition}), which
 * keeps the cells of a table row on one line.
 *
 * <p>Without tesseract a scanned PDF fails with an {@link OcrException}: this provider refuses to fall back to a model,
 * because the raw bytes of a client notice must never reach a free-tier model (see docs/PRIVATE_DATA.md).
 */
@Slf4j
public class LocalOcrProvider implements OcrProvider {

    public static final String NAME = "local";
    /** "Model" names reported in the usage so the run report says how the text was read; both are unpriced (cost 0). */
    public static final String ENGINE_PDFBOX = "pdfbox";
    public static final String ENGINE_TESSERACT = "tesseract";

    public static final String SCANNED_REFUSAL = "scanned PDF needs tesseract or a paid OCR provider; "
            + "refusing to send raw bytes to a free-tier model";

    /** A page whose text layer has fewer letters / digits than this is treated as scanned. */
    static final int MIN_CHARS_PER_PAGE = 40;
    static final int RENDER_DPI = 300;
    static final long TESSERACT_TIMEOUT_SECONDS = 180;

    /** Runs the tesseract command on one image file and returns its text; null when tesseract is not installed. */
    public interface TesseractRunner {
        /** True when the command is available on this machine. */
        boolean available();

        /** OCR text of one page image ({@code eng}). */
        String recognise(Path image) throws IOException;
    }

    private final TesseractRunner tesseract;

    public LocalOcrProvider() {
        this(new CliTesseract());
    }

    LocalOcrProvider(TesseractRunner tesseract) {
        this.tesseract = tesseract;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** True when the tesseract command is on the PATH (scanned PDFs and images can be read). */
    public boolean tesseractAvailable() {
        return tesseract != null && tesseract.available();
    }

    @Override
    public OcrResult process(byte[] fileBytes, String filename, String mimeType) {
        if (fileBytes == null || fileBytes.length == 0) throw new OcrException("local OCR: empty document");
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.ROOT) : "";
        boolean pdf = mime.contains("pdf") || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                || startsWithPdfHeader(fileBytes);
        return pdf ? processPdf(fileBytes) : processImage(fileBytes, filename);
    }

    // ---------------------------------------------------------------- PDF

    private OcrResult processPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) throw new OcrException("local OCR: the PDF has no pages");
            List<String> pageTexts = new ArrayList<>(pages);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            int textPages = 0;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = normalise(stripper.getText(doc));
                pageTexts.add(t);
                if (letterDigitCount(t) >= MIN_CHARS_PER_PAGE) textPages++;
            }
            // a digital PDF: every page has a text layer (a blank page in a text PDF is tolerated when most pages have text)
            if (textPages > 0 && textPages * 2 >= pages) {
                return new OcrResult(join(pageTexts), pages, NAME, usage(ENGINE_PDFBOX));
            }
            // scanned: render each page and run tesseract on it
            if (!tesseractAvailable()) {
                throw new OcrException("local OCR: " + SCANNED_REFUSAL + " (" + textPages + " of " + pages + " pages have a text layer)");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            Path tmp = Files.createTempDirectory("noticedesk-ocr-");
            try {
                for (int p = 0; p < pages; p++) {
                    String layer = pageTexts.get(p);
                    if (letterDigitCount(layer) >= MIN_CHARS_PER_PAGE) continue;   // a page with text keeps its text layer
                    BufferedImage img = renderer.renderImageWithDPI(p, RENDER_DPI, ImageType.GRAY);
                    Path png = tmp.resolve("page-" + (p + 1) + ".png");
                    ImageIO.write(img, "png", png.toFile());
                    pageTexts.set(p, normalise(tesseract.recognise(png)));
                    Files.deleteIfExists(png);
                }
            } finally {
                deleteQuietly(tmp);
            }
            return new OcrResult(join(pageTexts), pages, NAME, usage(ENGINE_TESSERACT));
        } catch (OcrException e) {
            throw e;
        } catch (IOException e) {
            throw new OcrException("local OCR: the PDF could not be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    // ---------------------------------------------------------------- images

    private OcrResult processImage(byte[] bytes, String filename) {
        if (!tesseractAvailable()) {
            throw new OcrException("local OCR: an image " + SCANNED_REFUSAL);
        }
        try {
            String ext = filename != null && filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".png";
            Path img = Files.createTempFile("noticedesk-ocr-", ext);
            try {
                Files.write(img, bytes);
                String text = normalise(tesseract.recognise(img));
                return new OcrResult(join(List.of(text)), 1, NAME, usage(ENGINE_TESSERACT));
            } finally {
                Files.deleteIfExists(img);
            }
        } catch (IOException e) {
            throw new OcrException("local OCR: the image could not be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Token usage of a local run: no tokens, the engine as the model name (unpriced, so the step costs 0). */
    static OcrResult.OcrUsage usage(String engine) {
        return new OcrResult.OcrUsage(NAME, engine, 0, 0, null);
    }

    /** "=== PAGE n ===" before each page's text, as the gemini provider is instructed to write. */
    static String join(List<String> pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            sb.append("=== PAGE ").append(i + 1).append(" ===\n");
            String t = pages.get(i);
            if (t != null && !t.isBlank()) sb.append(t.strip()).append('\n');
        }
        return sb.toString();
    }

    /** Windows line ends to '\n', trailing spaces cut, runs of three or more blank lines collapsed. */
    static String normalise(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
    }

    static int letterDigitCount(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) n++;
        return n;
    }

    private static boolean startsWithPdfHeader(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    s.forEach(p -> p.toFile().delete());
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // a left-over temp folder is harmless
        }
    }

    /** The system {@code tesseract} command ({@code tesseract <image> stdout -l eng}). */
    static final class CliTesseract implements TesseractRunner {
        private Boolean available;

        @Override
        public synchronized boolean available() {
            if (available == null) available = onPath("tesseract");
            return available;
        }

        @Override
        public String recognise(Path image) throws IOException {
            Process p = new ProcessBuilder("tesseract", image.toAbsolutePath().toString(), "stdout", "-l", "eng")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                byte[] out = p.getInputStream().readAllBytes();
                if (!p.waitFor(TESSERACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new OcrException("local OCR: tesseract timed out after " + TESSERACT_TIMEOUT_SECONDS + "s on one page");
                }
                if (p.exitValue() != 0) throw new OcrException("local OCR: tesseract exited with " + p.exitValue());
                return new String(out, StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OcrException("local OCR: interrupted while running tesseract", e);
            }
        }

        static boolean onPath(String command) {
            String path = System.getenv("PATH");
            if (path == null) return false;
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                File f = new File(dir, command);
                if (f.isFile() && f.canExecute()) return true;
            }
            return false;
        }
    }
}
