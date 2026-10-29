package com.noticedesk.api.testsupport;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a small, entirely synthetic notice-reply corpus in a temporary folder, in the layout
 * {@code GstCorpusMatcherService} and {@code CorpusRagSeederService} read ({@code pairing_manifest.jsonl},
 * {@code drafts/*.docx}, {@code notices/*.docx}). The firm's real corpus is never part of the repository
 * (docs/PRIVATE_DATA.md); tests point {@code drafting.gst-corpus-dir} at the folder this creates.
 */
public final class SyntheticCorpusFixture {

    private SyntheticCorpusFixture() {}

    /** One manifest row: serial, notice kind, draft type and the two file names. */
    public record Pair(String serial, String noticeKind, String draftType, String draftFile, String noticeFile) {}

    /** Three invented pairs: a Section 73 ITC mismatch reply, a Section 129 detention reply, an ASMT-10 reply. */
    public static final List<Pair> DEFAULT_PAIRS = List.of(
            new Pair("01", "scn_73", "Reply",
                    "Reply_01_Noticedesk_ITC_Mismatch_GSTR2A_3B_S73.docx",
                    "Notice_01_Noticedesk_ITC_Mismatch_GSTR2A_3B_S73.docx"),
            new Pair("02", "detention_notice", "Reply",
                    "Reply_02_Noticedesk_Detention_Eway_Bill_S129.docx",
                    "Notice_02_Noticedesk_Detention_Eway_Bill_S129.docx"),
            new Pair("03", "asmt10", "Reply",
                    "Reply_03_Noticedesk_Scrutiny_ASMT10.docx",
                    "Notice_03_Noticedesk_Scrutiny_ASMT10.docx"));

    /** Writes the default corpus under {@code dir} and returns {@code dir}. */
    public static Path create(Path dir) throws IOException {
        return create(dir, DEFAULT_PAIRS);
    }

    public static Path create(Path dir, List<Pair> pairs) throws IOException {
        Path drafts = Files.createDirectories(dir.resolve("drafts"));
        Path notices = Files.createDirectories(dir.resolve("notices"));
        StringBuilder manifest = new StringBuilder();
        for (Pair p : pairs) {
            manifest.append("{\"serial\":\"").append(p.serial())
                    .append("\",\"draft_file\":\"").append(p.draftFile())
                    .append("\",\"notice_file\":\"").append(p.noticeFile())
                    .append("\",\"notice_kind\":\"").append(p.noticeKind())
                    .append("\",\"draft_type\":\"").append(p.draftType()).append("\"}\n");
            writeDocx(drafts.resolve(p.draftFile()), List.of(
                    "BEFORE THE PROPER OFFICER, GOODS AND SERVICES TAX DEPARTMENT",
                    "REPLY ON BEHALF OF M/s [CLIENT_NAME], GSTIN [GSTIN]",
                    "Subject: reply to notice [NOTICE_NUMBER] (" + p.noticeKind() + ")",
                    "1. The noticee submits that the proposed demand is not sustainable in law or on facts.",
                    "2. The returns filed for the period were correct and complete; supporting records are enclosed.",
                    "3. The noticee prays that the proceedings be dropped.",
                    "For M/s [CLIENT_NAME], Authorised Signatory"));
            writeDocx(notices.resolve(p.noticeFile()), List.of(
                    "FORM GST notice (" + p.noticeKind() + ")",
                    "To, M/s [CLIENT_NAME], GSTIN [GSTIN]",
                    "You are required to show cause why the proposed demand should not be confirmed."));
        }
        Files.write(dir.resolve("pairing_manifest.jsonl"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static void writeDocx(Path path, List<String> paragraphs) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String text : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                p.createRun().setText(text);
            }
            try (OutputStream os = Files.newOutputStream(path)) {
                doc.write(os);
            }
        }
    }
}
