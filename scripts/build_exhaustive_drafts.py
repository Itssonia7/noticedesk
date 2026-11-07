import os
import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls

def set_cell_background(cell, fill_hex):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>')
    tcPr.append(shd)

def add_header_footer(doc, doc_title):
    section = doc.sections[0]
    section.top_margin = Inches(1)
    section.bottom_margin = Inches(1)
    section.left_margin = Inches(1)
    section.right_margin = Inches(1)
    
    header = section.header
    hp = header.paragraphs[0]
    hp.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    hrun = hp.add_run(f"NoticeDesk — Production Legal Draft | {doc_title}")
    hrun.font.name = 'Calibri'
    hrun.font.size = Pt(8.5)
    hrun.font.color.rgb = RGBColor(0x64, 0x74, 0x8B)
    
    footer = section.footer
    fp = footer.paragraphs[0]
    fp.alignment = WD_ALIGN_PARAGRAPH.LEFT
    frun = fp.add_run("CONFIDENTIAL & PRIVILEGED LEGAL DRAFTING — PREPARED FOR TAXPAYER")
    frun.font.name = 'Calibri'
    frun.font.size = Pt(8.5)
    frun.font.color.rgb = RGBColor(0x64, 0x74, 0x8B)

def build_production_hr_steel_docx(filename):
    doc = docx.Document()
    add_header_footer(doc, "M/s H R Steel (SCN Reply)")
    
    # Title Block
    p_main = doc.add_paragraph()
    p_main.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_main = p_main.add_run("BEFORE THE ASSISTANT COMMISSIONER / PROPER OFFICER OF CENTRAL TAX\nCGST & CENTRAL EXCISE, NASHIK COMMISSIONERATE\nDIVISION-SATPUR, NASHIK, MAHARASHTRA")
    r_main.font.name = 'Calibri'
    r_main.font.size = Pt(13.5)
    r_main.font.bold = True
    r_main.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
    
    doc.add_paragraph()
    
    # Notice Metadata Header
    p_sub = doc.add_paragraph()
    r_sub = p_sub.add_run(
        "SHOW CAUSE CUM DEMAND NOTICE NO: GEXCOM/AE/INV/GST/1886/2026\n"
        "CENTRALIZED SCN SR. NO: 07/26-27 DATED 2026\n"
        "DIN NO: GEXCOM/AE/INV/GST/1886/2026-AE-O/o COMMR-CGST-NASHIK\n"
        "IN THE MATTER OF: PROPOSED DEMAND OF CGST & SGST UNDER SECTION 73 / 74 OF CGST ACT, 2017\n\n"
        "IN RE: M/s H R Steel (Prop. Juned Aliahmed Chaudhari),\n"
        "GSTIN: 27AGZPC3957D1Z2,\n"
        "Address: Plot No.57, GUT No.19/1, Satpur Ambad Link Road, Chinchole, Ambad, Nashik, Maharashtra - 422010. ... NOTICEE / TAXPAYER"
    )
    r_sub.font.name = 'Calibri'
    r_sub.font.size = Pt(10)
    r_sub.font.bold = True
    p_sub.paragraph_format.space_after = Pt(12)
    
    # Title Box
    p_tbox = doc.add_paragraph()
    p_tbox.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_tbox = p_tbox.add_run("EXHAUSTIVE & DETAILED STATUTORY REPLY ON BEHALF OF THE NOTICEE")
    r_tbox.font.name = 'Calibri'
    r_tbox.font.size = Pt(12.5)
    r_tbox.font.bold = True
    r_tbox.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
    p_tbox.paragraph_format.space_after = Pt(12)
    
    sections = [
        ("I. MOST RESPECTFULLY SHOWETH:",
         "1. The Noticee, M/s H R Steel, a sole proprietorship concern duly registered under the Central Goods and Services Tax Act, 2017 ('CGST Act') and Maharashtra Goods and Services Tax Act, 2017 ('MGST Act') bearing GSTIN 27AGZPC3957D1Z2, represented by its Proprietor Mr. Juned Aliahmed Chaudhari, submits this comprehensive legal reply to the Show Cause Cum Demand Notice bearing No. GEXCOM/AE/INV/GST/1886/2026.\n\n"
         "2. At the outset, the Noticee denies each and every allegation, contention, proposal, and calculation contained in the Show Cause Notice ('SCN') save and except those that are specifically admitted herein. Nothing contained in the SCN shall be deemed to be accepted or admitted by the Noticee by implication or lack of specific traverse."),
        
        ("II. BRIEF FACTS & NATURE OF BUSINESS OPERATIONS:",
         "3. The Noticee is a legitimate, small-scale registered trader based in Nashik, Maharashtra, actively engaged in the wholesale and retail trading of timber products, sawn wood, and structural iron/steel materials falling under HSN Headings 44 and 72 (specifically wood sawn or chipped lengthwise, sliced or peeled, planed or sanded of thickness exceeding 6mm, plates, sheets, and related items).\n\n"
         "4. For the purpose of carrying out its trade, the Noticee purchases goods from regular registered suppliers located within Maharashtra and neighboring states. Every single procurement made by the Noticee is supported by:\n"
         "   a) Valid Tax Invoices issued by the suppliers under Section 31 of the CGST Act;\n"
         "   b) Valid e-Way Bills generated on the E-Way Bill portal under Rule 138;\n"
         "   c) Transporter bilties and weighbridge slip records proving physical movement of goods;\n"
         "   d) Bank payment vouchers proving 100% settlement of invoice consideration along with applicable GST via account payee cheques / NEFT / RTGS;\n"
         "   e) Stock ledger entries recording physical receipt of goods at Noticee's yard located at Satpur Ambad Link Road, Nashik.\n\n"
         "5. The Noticee has consistently discharged its tax liabilities and filed statutory monthly returns in Form GSTR-1 and Form GSTR-3B well within prescribed due dates."),
        
        ("III. PRELIMINARY OBJECTIONS — PROCEDURAL DISCREPANCIES & LACK OF MANDATORY CONSULTATION:",
         "6. FAILURE TO ISSUE FORM GST DRC-01A (PRE-SCN CONSULTATION):\n"
         "The statutory framework mandated under Rule 142(1A) of the CGST Rules, 2017 requires the proper officer to communicate details of tax, interest, and penalty in Part A of Form GST DRC-01A before serving a Show Cause Notice under Section 73(1) or Section 74(1). The underlying intent of Rule 142(1A) is to provide the taxpayer an opportunity to pay tax or clarify discrepancies at the pre-SCN stage. By failing to issue Form GST DRC-01A, the Department has bypassed statutory procedure, causing severe prejudice to the Noticee.\n\n"
         "7. VAGUE & UNCONCRETIZED ALLEGATIONS VIOLATING PRINCIPLES OF NATURAL JUSTICE:\n"
         "The SCN alleges that certain suppliers from whom the Noticee purchased goods were found 'non-existent' or 'cancelled retrospectively'. However, the SCN fails to provide invoice-wise breakdown, investigation reports, statements of third parties, or inspection logs relied upon by the Anti-Evasion wing. Hon'ble Supreme Court in CCE v. Brindavan Beverages (2007) 5 SCC 388 held that a Show Cause Notice which lacks precise factual particulars or fails to disclose relied-upon documents is void for violation of Natural Justice."),
        
        ("IV. DETAILED LEGAL SUBMISSIONS ON MERITS:",
         "8. FULL SATISFACTION OF STATUTORY CONDITIONS UNDER SECTION 16(2) OF THE CGST ACT:\n"
         "Section 16(2) of the CGST Act, 2017 lays down four statutory conditions for entitlement to Input Tax Credit:\n"
         "   • Condition (a) - Possession of Tax Invoice: The Noticee holds valid tax invoices containing all mandatory particulars under Rule 46.\n"
         "   • Condition (b) - Receipt of Goods: The goods under HSN 44 & 72 were physically received, unloaded, and entered into stock ledgers, corroborated by e-Way Bills and transport bilties.\n"
         "   • Condition (c) - Payment of Tax to Government: The Noticee paid the full invoice value including GST to the suppliers. The tax invoice carried explicit statutory certification by the supplier that tax was charged and payable. The Noticee cannot be asked to perform an impossible act ('lex non cogit ad impossibilia') of verifying whether the supplier subsequently deposited the tax into the Treasury.\n"
         "   • Condition (d) - Filing of Return: The Noticee regularly filed GSTR-3B returns.\n\n"
         "9. RETROSPECTIVE CANCELLATION OF SUPPLIER'S REGISTRATION CANNOT INVALIDE GENUINE PAST TRANSACTIONS:\n"
         "The Department's proposal to disallow ITC on the ground that a supplier's GSTIN was cancelled retrospectively is legally untenable. At the time of transaction, the supplier's GSTIN was active and verified as valid on the GST Portal. Retrospective cancellation of a supplier's registration by tax authorities does not operate to invalidate genuine commercial transactions executed prior to the date of cancellation.\n\n"
         "10. BINDING JUDICIAL PRECEDENTS DIRECTLY GOVERNING THE ISSUE:\n"
         "The Noticee relies upon the following settled legal precedents which directly govern this case:\n\n"
         "   a) Hon'ble Calcutta High Court in Suncraft Energy Venture Pvt. Ltd. v. Assistant Commissioner (2023) 153 taxmann.com 81 (Cal HC):\n"
         "      The High Court held that the Revenue cannot proceed against the purchasing buyer for reversal of ITC without first initiating recovery proceedings against the defaulting supplier, unless collusion is established by cogent evidence.\n\n"
         "   b) Hon'ble Madras High Court in D.Y. Beathel Enterprises v. State Tax Officer (2021) 127 taxmann.com 80 (Mad HC):\n"
         "      The High Court quashed demand orders passed against recipient buyers, holding that when the buyer has paid tax to the seller via banking channels, the officer must examine the seller and exhaust recovery remedies against the seller first.\n\n"
         "   c) Hon'ble Supreme Court in Union of India v. Bharti Airtel Ltd. (2021) 131 taxmann.com 319 (SC):\n"
         "      The Supreme Court affirmed that Input Tax Credit is a statutory creation under Section 16(1) and recipient buyers who fulfill statutory criteria cannot be deprived of credit due to system discrepancies.\n\n"
         "11. CBIC CIRCULAR NO. 183/15/2022-GST DATED 27.12.2022 & CIRCULAR NO. 193/05/2023-GST:\n"
         "The CBIC has clarified that mismatches between GSTR-2A/2B and GSTR-3B are not per se proof of evasion. Where the difference is supported by supplier declarations or certificates, the credit MUST be allowed."),
        
        ("V. UNJUSTIFIED INVOCATION OF EXTENDED PERIOD UNDER SECTION 74 & PROPOSED PENALTY:",
         "12. Section 74 of the CGST Act applies ONLY when tax has not been paid by reason of 'fraud', 'willful misstatement', or 'suppression of facts WITH INTENT TO EVADE TAX'.\n\n"
         "13. In the present case, every transaction was recorded in Noticee's audited books of accounts, purchase ledgers, and declared in monthly GSTR-1 and GSTR-3B returns. There was zero concealment or intent to evade tax. As held by the Hon'ble Supreme Court in Cosmic Radio v. CCE (1995) 75 ELT 721 and Tamil Nadu Housing Board v. CCE 1994 Supp (2) SCC 489, mere difference of interpretation or non-payment without active deliberate suppression does not attract extended period of limitation or equal penalty.\n\n"
         "14. Consequently, the proposal to invoke Section 74, levy interest under Section 50, and impose 100% penalty under Section 74 / Section 122 is illegal, bad in law, and liable to be dropped."),
        
        ("VI. RECAPITULATION TABLE OF SCN ALLEGATIONS VS NOTICEE LEGAL REBUTTAL:",
         "Exhaustive Summary Table:"),
        
        ("VII. PRAYER & RELIEF SOUGHT:",
         "In the premises aforesaid, the Noticee most respectfully prays that the Learned Officer be pleased to:\n\n"
         "   a) DROP the entire proposed demand of CGST and SGST tax along with interest under Section 50 and penalty under Section 74/122 in full;\n"
         "   b) HOLD that the Noticee has validly availed Input Tax Credit under Section 16 of the CGST Act, 2017;\n"
         "   c) GRANT an opportunity of Personal Hearing to the Noticee / authorized representative prior to passing any final order;\n"
         "   d) PASS such further order or directions as may be deemed fit and proper in the interest of equity and justice.")
    ]
    
    for title, text in sections:
        if title == "VI. RECAPITULATION TABLE OF SCN ALLEGATIONS VS NOTICEE LEGAL REBUTTAL:":
            p_h = doc.add_paragraph()
            r_h = p_h.add_run(title)
            r_h.font.name = 'Calibri'
            r_h.font.size = Pt(11.5)
            r_h.font.bold = True
            r_h.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
            
            table = doc.add_table(rows=4, cols=4)
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            headers = ["SCN Allegation Ground", "Proposed Tax Demand", "Noticee Statutory Defense", "Legal Result"]
            hdr_cells = table.rows[0].cells
            for idx, name in enumerate(headers):
                hdr_cells[idx].text = name
                set_cell_background(hdr_cells[idx], "1E3A8A")
                p = hdr_cells[idx].paragraphs[0]
                p.runs[0].font.bold = True
                p.runs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                p.runs[0].font.size = Pt(9)
                
            data = [
                ("Ineligible ITC from Alleged Cancelled Suppliers", "As per SCN Annexure", "Payments via bank; e-Way bills & stock ledgers intact; Suncraft Energy HC ratio applies", "ITC Valid & Retained"),
                ("HSN 44/72 Trade Valuation & ITC", "As per SCN Annexure", "Goods physically received & used in trade; Section 16(2) satisfied; CBIC Cir 183 applies", "Demand Dropped"),
                ("Section 74 Penalty & Extended Period", "100% Equal Penalty", "No fraud/suppression; all data reported in GSTR-1/3B; Cosmic Radio SC ratio applies", "Penalty Dropped")
            ]
            for row_idx, row_data in enumerate(data):
                row_cells = table.rows[row_idx+1].cells
                fill = "F8FAFC" if row_idx % 2 == 1 else "FFFFFF"
                for c_idx, val in enumerate(row_data):
                    row_cells[c_idx].text = val
                    set_cell_background(row_cells[c_idx], fill)
                    p = row_cells[c_idx].paragraphs[0]
                    p.runs[0].font.size = Pt(8.5)
                    p.runs[0].font.color.rgb = RGBColor(0x1E, 0x29, 0x3B)
            doc.add_paragraph()
            continue
            
        p_h = doc.add_paragraph()
        r_h = p_h.add_run(title)
        r_h.font.name = 'Calibri'
        r_h.font.size = Pt(11.5)
        r_h.font.bold = True
        r_h.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
        p_h.paragraph_format.space_before = Pt(8)
        p_h.paragraph_format.space_after = Pt(3)
        
        for para in text.split("\n\n"):
            p_b = doc.add_paragraph()
            r_b = p_b.add_run(para)
            r_b.font.name = 'Calibri'
            r_b.font.size = Pt(10)
            r_b.font.color.rgb = RGBColor(0x1E, 0x29, 0x3B)
            p_b.paragraph_format.space_after = Pt(5)
            p_b.paragraph_format.line_spacing = 1.15

    # Verification Block
    p_ver = doc.add_paragraph()
    p_ver.paragraph_format.space_before = Pt(16)
    r_ver = p_ver.add_run(
        "VERIFICATION\n"
        "I, Juned Aliahmed Chaudhari, Proprietor of M/s H R Steel, do hereby verify and declare that the contents of paragraphs I to VII above are true and correct to the best of my knowledge and belief, based on legal advice and statutory records of the firm. Nothing material has been concealed therefrom.\n\n"
        "Verified at Nashik on this _____ day of 2026.\n\n\n"
        "For M/s H R Steel\n\n\n"
        "(JUNED ALIAHMED CHAUDHARI)\n"
        "PROPRIETOR / AUTHORIZED SIGNATORY"
    )
    r_ver.font.name = 'Calibri'
    r_ver.font.size = Pt(10)
    r_ver.font.bold = True
    
    doc.save(filename)

def build_production_rfd08_docx(filename):
    doc = docx.Document()
    add_header_footer(doc, "M/s NIDIMO MONT PVT LTD (RFD-08 Reply)")
    
    # Title Block
    p_main = doc.add_paragraph()
    p_main.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_main = p_main.add_run("BEFORE THE ASSISTANT COMMISSIONER OF CENTRAL TAX & CGST\nMUMBAI COMMISSIONERATE / PROPER OFFICER OF REFUNDS")
    r_main.font.name = 'Calibri'
    r_main.font.size = Pt(13.5)
    r_main.font.bold = True
    r_main.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
    
    doc.add_paragraph()
    
    # Sub-header
    p_sub = doc.add_paragraph()
    r_sub = p_sub.add_run(
        "SHOW CAUSE NOTICE FOR REJECTION OF REFUND CLAIM (FORM GST RFD-08)\n"
        "APPLICATION ARN: AA2703260810849 | DATED: 14/03/2026\n"
        "PERIOD OF CLAIM: JANUARY 2026 | REFUND CLAIMED: RS. 1,78,63,659/-\n"
        "CATEGORY OF REFUND: ANY OTHER (IGST PAID ON EXPORT OF GOODS)\n\n"
        "IN RE: M/s NIDIMO MONT PRIVATE LIMITED,\n"
        "GSTIN: 27AAJCN7242F1ZC,\n"
        "Address: Mumbai, Maharashtra. ... APPLICANT / TAXPAYER"
    )
    r_sub.font.name = 'Calibri'
    r_sub.font.size = Pt(10)
    r_sub.font.bold = True
    p_sub.paragraph_format.space_after = Pt(12)
    
    # Title Box
    p_tbox = doc.add_paragraph()
    p_tbox.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_tbox = p_tbox.add_run("COMPREHENSIVE & EXHAUSTIVE STATUTORY REBUTTAL REPLY TO FORM GST RFD-08")
    r_tbox.font.name = 'Calibri'
    r_tbox.font.size = Pt(12.5)
    r_tbox.font.bold = True
    r_tbox.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
    p_tbox.paragraph_format.space_after = Pt(12)
    
    sections = [
        ("I. STATEMENT OF FACTS & REFUND APPLICATION BACKGROUND:",
         "1. The Applicant, M/s. NIDIMO MONT PRIVATE LIMITED, holding GSTIN 27AAJCN7242F1ZC, is an active corporate exporter engaged in the international trade and export of mobile phone hardware, electronic items, and telecommunication devices.\n\n"
         "2. On 14/03/2026, the Applicant submitted a valid online application for refund of IGST paid on export of goods under Form GST RFD-01 bearing ARN AA2703260810849 for the return period January 2026, claiming a total refund amount of Rs. 1,78,63,659/- (Rupees One Crore Seventy-Eight Lakhs Sixty-Three Thousand Six Hundred Fifty-Nine Only).\n\n"
         "3. In response to the said application, the Learned Officer issued a Show Cause Notice in Form GST RFD-08 proposing to disallow/reject the refund claim on three specific discrepancy grounds:\n"
         "   Discrepancy (a): Alleged excess ITC claimed in GSTR-3B vs GSTR-2B amounting to Rs. 3,51,274/-;\n"
         "   Discrepancy (b): Alleged ineligible ITC from Level-2 supplier (M/s Shree Shyam Mobility) cancelled suo-moto w.e.f. 25/04/2025 amounting to Rs. 21,10,119/-;\n"
         "   Discrepancy (c): Alleged ITC claimed not in furtherance of business.\n\n"
         "4. The Applicant submits this comprehensive written reply controverting each proposed rejection ground with legal provisions, CBIC Circulars, and binding judicial precedents."),
        
        ("II. REBUTTAL TO DISCREPANCY (A) — GSTR-3B VS GSTR-2B ITC DIFFERENCE (RS. 3,51,274/-):",
         "5. STATUTORY PROVISIONS & CBIC CIRCULAR NO. 183/15/2022-GST:\n"
         "The SCN alleges that ITC of Rs. 3,51,274/- pertaining to supplier Mahavir Communications (GSTIN: 27AMAPB2309R1Z2) was rejected in GSTR-2B of Jan-2026 and claimed in GSTR-3B. The Applicant submits that GSTR-2B is a dynamic, auto-generated statement and timing differences between supplier reporting and recipient claim do not extinguish the recipient's statutory right under Section 16(1).\n\n"
         "6. CBIC Circular No. 183/15/2022-GST dated 27.12.2022 explicitly lays down the procedure for handling GSTR-2A/2B vs GSTR-3B mismatches. Where the mismatch is less than Rs. 5 Lakhs per supplier, a certificate from the supplier confirming payment of tax is sufficient to validate the credit.\n\n"
         "7. The Applicant possesses original tax invoices, bank payment receipts, and a valid supplier declaration from Mahavir Communications confirming full payment of tax into the public exchequer. Therefore, disallowance of Rs. 3,51,274/- from the refund claim is illegal."),
        
        ("III. REBUTTAL TO DISCREPANCY (B) — LEVEL-2 CANCELLED SUPPLIER ALLEGATION (RS. 21,10,119/-):",
         "8. ABSENCE OF PRIVITY OF CONTRACT & LEGAL UNTENABILITY:\n"
         "The SCN states that upon verification up to Level-2, supplier M/s Shree Shyam Mobility (GSTIN: 24AFOFS7757K1Z5) was cancelled suo-moto w.e.f 25/04/2025, and therefore IGST credit of Rs. 21,10,119/- passed through Level-1 supplier M/s NIDIMO INTERNATIONAL (GSTIN: 27AAWFN1025A1ZI) is ineligible.\n\n"
         "9. NO RETROSPECTIVE PENALIZATION OF RECIPIENT FOR DEEP-TIER SUPPLIER DEFAULTS:\n"
         "The Applicant purchased goods directly from Level-1 supplier Nidimo International. The Applicant has no direct commercial relationship, control, or privity of contract with Level-2 supplier Shree Shyam Mobility. At the time of transaction, Nidimo International was active, issued valid tax invoices, and was paid full consideration including GST through bank channels.\n\n"
         "10. BINDING HIGH COURT PRECEDENTS ON DEEP-TIER SUPPLIER VERIFICATION:\n"
         "  • Hon'ble Calcutta High Court in Suncraft Energy Venture Pvt. Ltd. (2023): Held that the tax authority cannot penalize a bona fide purchasing recipient for the default of an upstream supplier without first taking action against the defaulting supplier.\n"
         "  • Hon'ble Gujarat High Court in Surat Trade and Industry Association (2022): Confirmed that automatic denial of refund based on deep-tier supplier cancellation violates Article 19(1)(g) and Article 300A of the Constitution of India.\n"
         "  • Hon'ble Madras High Court in D.Y. Beathel Enterprises (2021): Held that recovery must be directed against the defaulting seller first before touching the buyer's credit or refund."),
        
        ("IV. REBUTTAL TO DISCREPANCY (C) — ITC IN FURTHERANCE OF BUSINESS (SECTION 16(1)):",
         "11. All mobile phone hardware and electronic items purchased were exported outside India under Shipping Bills and Bills of Lading. The goods were supplied directly in furtherance of the Applicant's export business under Section 16(1) of the CGST Act.\n\n"
         "12. Complete Foreign Inward Remittance Certificates ('FIRC') and Bank Realization Certificates ('BRC') issued by authorized dealer banks confirm receipt of convertible foreign exchange. Therefore, the allegation that purchases were not in furtherance of business is baseless."),
        
        ("V. SUMMARY TABLE OF REFUND REJECTION DISCREPANCIES & LEGAL REBUTTAL:",
         "Summary Table of RFD-08 Rejection Grounds:"),
        
        ("VI. PRAYER & RELIEF SOUGHT:",
         "In view of the detailed statutory submissions, CBIC circulars, and binding judicial rulings, the Applicant prays that the Learned Officer be pleased to:\n\n"
         "  a) WITHDRAW the proposed rejection of refund in Form GST RFD-08 in its entirety;\n"
         "  b) SANCTION AND RELEASE the full refund claim of Rs. 1,78,63,659/- (Rupees One Crore Seventy-Eight Lakhs Sixty-Three Thousand Six Hundred Fifty-Nine Only) under ARN AA2703260810849 into the Applicant's designated bank account along with statutory interest under Section 56;\n"
         "  c) GRANT an opportunity of Personal Hearing to the Applicant / representative before taking any final decision;\n"
         "  d) PASS such further order(s) as may be deemed just and necessary.")
    ]
    
    for title, text in sections:
        if title == "V. SUMMARY TABLE OF REFUND REJECTION DISCREPANCIES & LEGAL REBUTTAL:":
            p_h = doc.add_paragraph()
            r_h = p_h.add_run(title)
            r_h.font.name = 'Calibri'
            r_h.font.size = Pt(11.5)
            r_h.font.bold = True
            r_h.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
            
            table = doc.add_table(rows=4, cols=4)
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            headers = ["RFD-08 Discrepancy", "Proposed Disallowance", "Applicant Statutory Defense", "Legal Outcome"]
            hdr_cells = table.rows[0].cells
            for idx, name in enumerate(headers):
                hdr_cells[idx].text = name
                set_cell_background(hdr_cells[idx], "1E3A8A")
                p = hdr_cells[idx].paragraphs[0]
                p.runs[0].font.bold = True
                p.runs[0].font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                p.runs[0].font.size = Pt(9)
                
            data = [
                ("Discrepancy (a): GSTR-3B vs 2B Mismatch", "Rs. 3,51,274/-", "CBIC Circular 183/2022 applies; supplier certificate held; Section 16(2) fulfilled", "Refund Allowable"),
                ("Discrepancy (b): Level-2 Cancelled Supplier", "Rs. 21,10,119/-", "No privity of contract with L-2; Suncraft Energy & D.Y. Beathel HC ratios apply", "Refund Allowable"),
                ("Discrepancy (c): Business Furtherance", "Rs. 1,54,02,266/- (Balance)", "Export goods supported by Shipping Bills, BRC/FIRC; Section 16(1) complied", "Refund Allowable")
            ]
            for row_idx, row_data in enumerate(data):
                row_cells = table.rows[row_idx+1].cells
                fill = "F8FAFC" if row_idx % 2 == 1 else "FFFFFF"
                for c_idx, val in enumerate(row_data):
                    row_cells[c_idx].text = val
                    set_cell_background(row_cells[c_idx], fill)
                    p = row_cells[c_idx].paragraphs[0]
                    p.runs[0].font.size = Pt(8.5)
                    p.runs[0].font.color.rgb = RGBColor(0x1E, 0x29, 0x3B)
            doc.add_paragraph()
            continue
            
        p_h = doc.add_paragraph()
        r_h = p_h.add_run(title)
        r_h.font.name = 'Calibri'
        r_h.font.size = Pt(11.5)
        r_h.font.bold = True
        r_h.font.color.rgb = RGBColor(0x1E, 0x3A, 0x8A)
        p_h.paragraph_format.space_before = Pt(8)
        p_h.paragraph_format.space_after = Pt(3)
        
        for para in text.split("\n\n"):
            p_b = doc.add_paragraph()
            r_b = p_b.add_run(para)
            r_b.font.name = 'Calibri'
            r_b.font.size = Pt(10)
            r_b.font.color.rgb = RGBColor(0x1E, 0x29, 0x3B)
            p_b.paragraph_format.space_after = Pt(5)
            p_b.paragraph_format.line_spacing = 1.15

    # Verification
    p_ver = doc.add_paragraph()
    p_ver.paragraph_format.space_before = Pt(16)
    r_ver = p_ver.add_run(
        "VERIFICATION\n"
        "I, Director / Authorized Signatory of M/s NIDIMO MONT PRIVATE LIMITED, do hereby verify and declare that the facts stated in paragraphs I to VI above are true and correct to the best of my knowledge and belief, based on statutory books of accounts, shipping bills, and bank realization certificates of the company.\n\n"
        "Verified at Mumbai on this _____ day of 2026.\n\n\n"
        "For M/s NIDIMO MONT PRIVATE LIMITED\n\n\n"
        "(DIRECTOR / AUTHORIZED SIGNATORY)"
    )
    r_ver.font.name = 'Calibri'
    r_ver.font.size = Pt(10)
    r_ver.font.bold = True
    
    doc.save(filename)

if __name__ == "__main__":
    out_dir = "/home/sonia/internship/noticedesk/output"
    docx1 = os.path.join(out_dir, "Reply_HR_Steel_SCN.docx")
    docx2 = os.path.join(out_dir, "Reply_1st_RFD-08_Notice.docx")
    build_production_hr_steel_docx(docx1)
    build_production_rfd08_docx(docx2)
