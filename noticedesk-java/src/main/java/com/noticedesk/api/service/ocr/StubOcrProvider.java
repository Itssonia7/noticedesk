package com.noticedesk.api.service.ocr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

public class StubOcrProvider implements OcrProvider {

    private static final Pattern TJ_PATTERN = Pattern.compile("\\((.*?)\\)\\s*T[jJ]");

    @Override
    public String getName() { return "stub"; }

    @Override
    public OcrResult process(byte[] fileBytes, String filename, String mimeType) {
        String extractedText = extractPdfText(fileBytes);
        if (extractedText != null && !extractedText.isBlank()) {
            return new OcrResult(extractedText, 1, "stub");
        }

        return new OcrResult(
                "STUB OCR TEXT: Notice for Acme Manufacturing Pvt Ltd. " +
                "GSTIN: 27ABCDE1234F1Z5. PAN: ABCDE1234F. " +
                "Financial Year 2022-23. Authority: CGST Mumbai South.",
                1,
                "stub"
        );
    }

    private String extractPdfText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            StringBuilder sb = new StringBuilder();
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            int idx = 0;
            while ((idx = raw.indexOf("stream", idx)) != -1) {
                int start = idx + 6;
                if (start < raw.length() && raw.charAt(start) == '\r') start++;
                if (start < raw.length() && raw.charAt(start) == '\n') start++;
                int end = raw.indexOf("endstream", start);
                if (end == -1) break;

                try {
                    byte[] streamBytes = Arrays.copyOfRange(bytes, start, end);
                    int len = streamBytes.length;
                    while (len > 0 && (streamBytes[len - 1] == '\n' || streamBytes[len - 1] == '\r' || streamBytes[len - 1] == ' ')) {
                        len--;
                    }

                    Inflater inflater = new Inflater();
                    inflater.setInput(streamBytes, 0, len);
                    byte[] buffer = new byte[4096];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    while (!inflater.finished()) {
                        int count = inflater.inflate(buffer);
                        if (count == 0) break;
                        baos.write(buffer, 0, count);
                    }
                    inflater.end();

                    String decompressed = baos.toString(StandardCharsets.ISO_8859_1);
                    Matcher matcher = TJ_PATTERN.matcher(decompressed);
                    while (matcher.find()) {
                        String txt = matcher.group(1).replace("\\(", "(").replace("\\)", ")");
                        sb.append(txt).append("\n");
                    }
                } catch (Exception ignored) {}
                idx = end + 9;
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
