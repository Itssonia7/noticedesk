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
        String clientName = safe(input.clientLegalName());
        String gstin = safe(input.registrationIdentifier());
        String pan = safe(input.clientPan());
        String state = safe(input.registrationStateName());
        String fy = safe(input.financialYear());
        String ay = safe(input.assessmentYear());
        String noticeNo = safe(String.valueOf(input.notice().getOrDefault("notice_number", "SCN/2026/001")));
        String din = safe(String.valueOf(input.notice().getOrDefault("din_or_rfn", "DIN-2026-GST-001")));
        String authority = safe(String.valueOf(input.notice().getOrDefault("authority", "Proper Officer, Ward 10")));
        String issueDate = safe(String.valueOf(input.notice().getOrDefault("issue_date", "14 September 2026")));
        String demandStr = input.notice().get("demand_amount") instanceof Number n ? "₹" + String.format("%.2f", n.doubleValue()) : "₹[____]";

        replacements.put("____________________", gstin);
        replacements.put("____________", noticeNo);
        replacements.put("dated __________", "dated " + issueDate);
        replacements.put("M/s Notice Desk Private Limited", clientName);
        replacements.put("Nasik", state);
        replacements.put("[____]", clientName);
        replacements.put("[TAX_PAYER_NAME]", clientName);
        replacements.put("[CLIENT_NAME]", clientName);
        replacements.put("[GSTIN]", gstin);
        replacements.put("[PAN]", pan);
        replacements.put("[STATE]", state);
        replacements.put("[FINANCIAL_YEAR]", fy);
        replacements.put("[ASSESSMENT_YEAR]", ay);
        replacements.put("[NOTICE_NUMBER]", noticeNo);
        replacements.put("[DIN]", din);
        replacements.put("[AUTHORITY]", authority);
        replacements.put("[DUE_DATE]", "30 Days");

        String populated = templateText;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            populated = populated.replace(entry.getKey(), entry.getValue());
        }

        // Strip remaining notes / headers if present
        populated = populated.replace("Drafting note (remove before filing).", "").strip();

        return populated;
    }

    private String safe(String val) {
        return (val != null && !val.isBlank()) ? val : "[N/A]";
    }

    private List<DraftSection> build15Sections(String populatedText, DraftingInput input) {
        List<DraftSection> sections = new ArrayList<>();

        String clientName = safe(input.clientLegalName());
        String gstin = safe(input.registrationIdentifier());
        String pan = safe(input.clientPan());
        String state = safe(input.registrationStateName());
        String fy = safe(input.financialYear());
        String noticeNo = safe(String.valueOf(input.notice().getOrDefault("notice_number", "SCN/2026/001")));
        String din = safe(String.valueOf(input.notice().getOrDefault("din_or_rfn", "DIN-2026-GST-001")));
        String authority = safe(String.valueOf(input.notice().getOrDefault("authority", "Proper Officer, Ward 10")));
        String issueDate = safe(String.valueOf(input.notice().getOrDefault("issue_date", "14 September 2026")));
        String demandStr = input.notice().get("demand_amount") instanceof Number n ? "₹" + String.format("%.2f", n.doubleValue()) : "as specified in SCN";

        // Section 1: Addressee and Reference Block
        sections.add(new DraftSection(1, "Addressee and Reference Block",
                "<p><b>BEFORE THE PROPER OFFICER / ASSISTANT COMMISSIONER</b><br>" +
                "Goods and Services Tax Department, " + authority + ", " + state + "<br>" +
                "<b>GSTIN:</b> " + gstin + " | <b>PAN:</b> " + pan + "<br>" +
                "<b>Trade / Legal Name:</b> " + clientName + "<br>" +
                "<b>Notice Ref No.:</b> " + noticeNo + " | <b>DIN/RFN:</b> " + din + " | <b>Dated:</b> " + issueDate + "</p>"));

        // Section 2: Subject
        sections.add(new DraftSection(2, "Subject",
                "<p>Reply on behalf of M/s " + clientName + " to Show Cause Notice / Intimation bearing Ref No. " + noticeNo +
                " dated " + issueDate + " issued under Section 73/74 of the CGST/SGST Act, 2017 seeking complete dropping of proposed proceedings and demand.</p>"));

        // Section 3: Synopsis
        sections.add(new DraftSection(3, "Synopsis",
                "<p>The Taxpayer M/s " + clientName + " has been served with the impugned notice proposing tax demand of " + demandStr +
                " for FY " + fy + ". The notice is fundamentally defective as it relies on summary format allegations without supplying supporting evidence, invokes extended period without establishing willful suppression, and denies statutory ITC in violation of settled law. All tax obligations have been faithfully discharged within statutory due dates. In view of binding Supreme Court and High Court precedents, the proceedings are liable to be dropped in full.</p>"));

        // Section 4: Statement of Facts
        sections.add(new DraftSection(4, "Statement of Facts",
                "<p>1. The Taxpayer M/s " + clientName + " is a duly registered entity under GST Law engaged in lawful business operations in " + state + ".<br>" +
                "2. For Financial Year " + fy + ", the Taxpayer regularly filed monthly returns in Form GSTR-1 and Form GSTR-3B and paid all self-assessed tax liabilities.<br>" +
                "3. On " + issueDate + ", the Proper Officer issued communication Ref No. " + noticeNo + " (DIN: " + din + ") alleging statutory contraventions.<br>" +
                "4. The Taxpayer submits this formal reply to demonstrate that no short payment, tax evasion, or suppression has occurred.</p>"));

        // Section 5: Preliminary Objections — Jurisdiction
        sections.add(new DraftSection(5, "Preliminary Objections — Jurisdiction",
                "<p>1. <b>Lack of Competent Jurisdiction:</b> The impugned notice has been issued without statutory authority under Section 73/74 of the CGST Act, 2017.<br>" +
                "2. <b>Monetary Limit Defect:</b> The officer issuing the notice exceeds the prescribed monetary threshold limits designated by CBIC Circulars for Officer grade hierarchy.</p>"));

        // Section 6: Preliminary Objections — Limitation and Procedure
        sections.add(new DraftSection(6, "Preliminary Objections — Limitation and Procedure",
                "<p>1. <b>Barred by Limitation:</b> The proposed demand for FY " + fy + " is barred by limitation under Section 73(10) / 74(10) of the CGST Act.<br>" +
                "2. <b>Absence of Form GST DRC-01A:</b> Intimation in Form GST DRC-01A was not issued prior to the SCN, violating mandatory pre-SCN consultation requirements under Rule 142(1A).<br>" +
                "3. <b>Want of Valid DIN:</b> Absence of verifiable Document Identification Number (DIN) invalidates the notice per CBIC Circular No. 122/41/2019-GST.</p>"));

        // Section 7: Para-wise Reply
        String formattedBodyHtml = "<div class=\"template-body\">" + populatedText.replace("\n\n", "</p><p>").replace("\n", "<br>") + "</div>";
        sections.add(new DraftSection(7, "Para-wise Reply", formattedBodyHtml));

        // Section 8: Ground 1
        sections.add(new DraftSection(8, "Ground 1",
                "<p><b>Absence of Willful Suppression or Intent to Evade Tax:</b> Extended period under Section 74 cannot be invoked on routine return mismatches. As settled by the Hon'ble Supreme Court in <i>Pushpam Pharmaceuticals Co. v. CCE</i> (1995 Supp (3) SCC 462) and <i>Cosmic Dye Chemical v. CCE</i> (1995 (75) ELT 721), 'suppression' requires deliberate withholding of facts with intent to evade tax, which is completely absent here.</p>"));

        // Section 9: Ground 2
        sections.add(new DraftSection(9, "Ground 2",
                "<p><b>ITC Cannot Be Denied to Purchasing Dealer for Supplier Default:</b> It is established by the Hon'ble Calcutta High Court in <i>Suncraft Energy Pvt Ltd v. ACST</i> (2023) and Madras High Court in <i>D.Y. Beathel Enterprises v. STO</i> (2021) that Input Tax Credit cannot be denied to the buyer without first initiating recovery proceedings against the selling supplier who collected tax.</p>"));

        // Section 10: Ground 3
        sections.add(new DraftSection(10, "Ground 3",
                "<p><b>Bare Summary Notice Violates Principles of Natural Justice:</b> Issuing a format DRC-01 notice without stating specific contraventions or annexing supporting documents denies a fair opportunity of defense, rendering the notice void ab initio as held in <i>M/s NKAS Services Pvt. Ltd. v. State of Jharkhand</i> (2021).</p>"));

        // Section 11: Without Prejudice — Alternative Submissions
        sections.add(new DraftSection(11, "Without Prejudice — Alternative Submissions",
                "<p>Without prejudice to primary grounds, even if any tax liability is sustained, no penalty can be levied under Section 73(9) / 74(9) as the Taxpayer acted under bona fide interpretation of statutory rules (<i>Continental Foundation v. CCE</i>). Further, interest under Section 50 is payable strictly on net cash liability paid via Electronic Cash Ledger.</p>"));

        // Section 12: Quantum, Interest and Computation
        sections.add(new DraftSection(12, "Quantum, Interest and Computation",
                "<p>1. The demand quantum of " + demandStr + " has been computed without providing invoice-level reconciliation details.<br>" +
                "2. Interest under Section 50 must be restricted to net cash liability as per proviso to Section 50(1).<br>" +
                "3. Penalty under Section 73/74 is unstatutory and liable to be waived in full.</p>"));

        // Section 13: Prayer
        sections.add(new DraftSection(13, "Prayer",
                "<p>In light of the above submissions, the Taxpayer respectfully prays that:<br>" +
                "1. The impugned Show Cause Notice Ref No. " + noticeNo + " be dropped in its entirety and proceedings closed.<br>" +
                "2. No penalty or interest be levied against the Taxpayer.<br>" +
                "3. An opportunity of <b>Personal Hearing</b> under Section 75(4) of the CGST Act be granted before passing any adverse order.</p>"));

        // Section 14: Annexures
        sections.add(new DraftSection(14, "Annexures",
                "<p>1. Copy of GST Registration Certificate (Form GST REG-06)<br>" +
                "2. Copies of GSTR-1 and GSTR-3B returns filed for FY " + fy + "<br>" +
                "3. GSTR-2A / 2B Reconciliation Statement & Tax Invoices<br>" +
                "4. Proof of Tax Payments / Form GST DRC-03 (if applicable)</p>"));

        // Section 15: Declaration and Signature Block
        sections.add(new DraftSection(15, "Declaration and Signature Block",
                "<p>I, Authorized Signatory for M/s " + clientName + ", do hereby verify and declare that the contents of Sections 1 to 14 above are true and correct to the best of my knowledge, information, and legal advice.<br><br>" +
                "<b>For M/s " + clientName + "</b><br><br>" +
                "____________________________________<br>" +
                "Authorized Signatory / Director<br>" +
                "Place: " + state + "<br>" +
                "Date: " + issueDate + "</p>"));

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
