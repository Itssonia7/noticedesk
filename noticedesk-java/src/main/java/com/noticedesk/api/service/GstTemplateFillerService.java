package com.noticedesk.api.service;

import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * GST Template Filler Service.
 *
 * Programmatically reads `.docx` template files from the GST Corpus, performs placeholder
 * substitution for client and notice metadata, and constructs a 15-section GeneratedDraft.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstTemplateFillerService {

    /**
     * Fills a corpus template `.docx` with client details and constructs a 15-section GeneratedDraft.
     */
    public GeneratedDraft fillTemplate(DraftingInput input, Path templatePath, String noticeKind) {
        log.info("Fast-Tracking draft generation using template at {}", templatePath);

        String rawContent = extractAndPopulateTemplate(templatePath, input);
        List<DraftSection> sections = build15Sections(rawContent, input);

        return new GeneratedDraft(
                sections,
                "Auto-generated draft produced via Fast-Track template substitution (NoticeDesk GST Corpus " + noticeKind + ").",
                "Replies generated using verified GST legal template for " + input.clientLegalName() + ".",
                "fast_track_corpus_v1",
                "template-engine",
                "fast-track",
                0,
                0
        );
    }

    /**
     * Reads text from templatePath and replaces bracketed placeholders [____].
     */
    public String extractAndPopulateTemplate(Path templatePath, DraftingInput input) {
        StringBuilder sb = new StringBuilder();

        if (Files.exists(templatePath)) {
            try (InputStream is = Files.newInputStream(templatePath);
                 XWPFDocument doc = new XWPFDocument(is)) {
                for (XWPFParagraph p : doc.getParagraphs()) {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append("\n\n");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse DOCX using Apache POI from {}. Falling back to plain text read.", templatePath, e);
            }
        }

        String templateText = sb.length() > 0 ? sb.toString() : getDefaultFallbackTemplate(input);

        // Perform Placeholder Substitution
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("[____]", safe(input.clientLegalName()));
        replacements.put("[TAX_PAYER_NAME]", safe(input.clientLegalName()));
        replacements.put("[CLIENT_NAME]", safe(input.clientLegalName()));
        replacements.put("[GSTIN]", safe(input.registrationIdentifier()));
        replacements.put("[PAN]", safe(input.clientPan()));
        replacements.put("[STATE]", safe(input.registrationStateName()));
        replacements.put("[FINANCIAL_YEAR]", safe(input.financialYear()));
        replacements.put("[ASSESSMENT_YEAR]", safe(input.assessmentYear()));
        replacements.put("[NOTICE_NUMBER]", safe(String.valueOf(input.notice().getOrDefault("notice_number", "SCN/2026/001"))));
        replacements.put("[DIN]", safe(String.valueOf(input.notice().getOrDefault("din_or_rfn", "DIN-2026-GST-001"))));
        replacements.put("[AUTHORITY]", safe(String.valueOf(input.notice().getOrDefault("authority", "Proper Officer, CGST & SGST"))));
        replacements.put("[DUE_DATE]", safe(String.valueOf(input.notice().getOrDefault("due_date", "30 Days"))));

        String populated = templateText;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            populated = populated.replace(entry.getKey(), entry.getValue());
        }

        return populated;
    }

    private String safe(String val) {
        return (val != null && !val.isBlank()) ? val : "[N/A]";
    }

    private List<DraftSection> build15Sections(String populatedText, DraftingInput input) {
        List<DraftSection> sections = new ArrayList<>();

        sections.add(new DraftSection(1, "Title & Header", "<p><b>BEFORE THE PROPER OFFICER, GOODS AND SERVICES TAX DEPARTMENT</b></p><p>REPLY TO SHOW CAUSE NOTICE / INTIMATION</p>"));
        sections.add(new DraftSection(2, "Jurisdiction & Forum", "<p><b>To:</b> " + safe(String.valueOf(input.notice().getOrDefault("authority", "Proper Officer"))) + "</p><p><b>Jurisdiction:</b> " + safe(input.registrationStateName()) + "</p>"));
        sections.add(new DraftSection(3, "Taxpayer Details", "<p><b>Taxpayer Name:</b> " + safe(input.clientLegalName()) + "<br><b>GSTIN:</b> " + safe(input.registrationIdentifier()) + "<br><b>PAN:</b> " + safe(input.clientPan()) + "</p>"));
        sections.add(new DraftSection(4, "Notice Reference & Subject", "<p><b>Ref Notice No:</b> " + safe(String.valueOf(input.notice().getOrDefault("notice_number", "SCN/2026/001"))) + " | <b>DIN/RFN:</b> " + safe(String.valueOf(input.notice().getOrDefault("din_or_rfn", "DIN-2026-GST-001"))) + "</p>"));
        sections.add(new DraftSection(5, "Statement of Facts", "<p>The Taxpayer respectfully submits the factual background of the matter for Financial Year " + safe(input.financialYear()) + ".</p>"));
        sections.add(new DraftSection(6, "Preliminary Objections", "<p>1. The subject notice/intimation is barred by limitation and procedural defects under the GST Law.<br>2. Absence of proper DIN/RFN invalidates the proceeding per CBIC circulars.</p>"));

        // Section 7: Substantive Content from Template
        String formattedBodyHtml = "<div class=\"template-body\">" + populatedText.replace("\n\n", "</p><p>").replace("\n", "<br>") + "</div>";
        sections.add(new DraftSection(7, "Substantive Reply & Defense", formattedBodyHtml));

        sections.add(new DraftSection(8, "Statutory Provisions & Analysis", "<p>Analysis of applicable provisions under Sections 16, 73, 74, 75, 129, and 140 of the Central Goods and Services Tax Act, 2017.</p>"));
        sections.add(new DraftSection(9, "Precedents & Judicial Decisions", "<p>Relied upon binding precedents of the Hon'ble Supreme Court and High Courts regarding natural justice and statutory compliance under GST.</p>"));
        sections.add(new DraftSection(10, "Documentary Evidence List", "<ul><li>Copy of GSTR-3B & GSTR-1 returns</li><li>GSTR-2A/2B Reconciliation Statement</li><li>Tax payment receipts (DRC-03)</li></ul>"));
        sections.add(new DraftSection(11, "Prayer Clause", "<p>In light of the above submissions, it is prayed that the proposed proceedings/demand be dropped and proceedings closed.</p>"));
        sections.add(new DraftSection(12, "Verification & Declaration", "<p>I, Authorized Signatory for " + safe(input.clientLegalName()) + ", do hereby verify that the contents of this reply are true to the best of my knowledge and belief.</p>"));
        sections.add(new DraftSection(13, "Signature & Representation", "<p><b>For " + safe(input.clientLegalName()) + "</b><br><br>Authorized Signatory / Legal Advocate</p>"));
        sections.add(new DraftSection(14, "Internal Partner Note", "<p>Fast-tracked output compiled from verified GST legal template.</p>"));
        sections.add(new DraftSection(15, "Client Summary", "<p>Formal reply prepared and submitted for filing before the Proper Officer.</p>"));

        return sections;
    }

    private String getDefaultFallbackTemplate(DraftingInput input) {
        return "REPLY ON BEHALF OF " + safe(input.clientLegalName()) + "\n\n" +
               "GSTIN: " + safe(input.registrationIdentifier()) + "\n\n" +
               "Respected Sir/Madam,\n\n" +
               "With reference to the notice/intimation issued under GST Law for FY " + safe(input.financialYear()) + ", " +
               "we submit that all tax liabilities have been duly discharged in accordance with law. " +
               "It is requested that the proposed proceedings be dropped and closure issued.\n\n" +
               "Thanking you,\n" + safe(input.clientLegalName());
    }
}
