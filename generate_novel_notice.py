from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch

c = canvas.Canvas("/home/sonia/internship/noticedesk/novel_crypto_gst_notice.pdf", pagesize=A4)
text = c.beginText(1 * inch, 10.5 * inch)
text.setFont("Helvetica", 12)

content = [
    "GOVERNMENT OF INDIA",
    "DEPARTMENT OF REVENUE",
    "CENTRAL BOARD OF INDIRECT TAXES AND CUSTOMS",
    "SUPERINTENDENT OF CENTRAL TAX",
    "MUMBAI ZONE, WARD 42",
    "",
    "Form GST ASMT-10",
    "[See Rule 68]",
    "Notice for Intimating Discrepancies in the Return after Scrutiny",
    "",
    "To,",
    "Acme Industries Private Limited",
    "GSTIN: 27AAACA9876B1Z5",
    "Address: 142, Nariman Point, Mumbai, Maharashtra 400021",
    "",
    "Date: 15-Oct-2026",
    "Financial Year: 2024-25",
    "Tax Period: April 2024 to March 2025",
    "",
    "Subject: Intimation of Discrepancy regarding Input Tax Credit claimed on Crypto-Asset Mining Equipment.",
    "",
    "Dear Taxpayer,",
    "",
    "During the scrutiny of your GSTR-3B and GSTR-2B returns for the aforementioned tax period,",
    "the following discrepancy was noticed:",
    "",
    "1. ITC of Rs. 45,50,000/- has been availed on the purchase of 'Application-Specific Integrated",
    "Circuit (ASIC) Miners' and related high-capacity cooling equipment.",
    "",
    "2. As per the business profile registered, your firm provides 'Chartered Accountancy Services'",
    "(SAC 99822). The procurement of cryptocurrency mining equipment does not appear to be used or",
    "intended to be used in the course or furtherance of your registered business.",
    "",
    "3. Therefore, the ITC claimed is in contravention of Section 16(1) of the CGST Act, 2017,",
    "which allows credit only for goods or services used in the furtherance of business.",
    "",
    "4. Furthermore, trading or mining in Virtual Digital Assets (VDAs) does not constitute a",
    "taxable supply under your current GST registration.",
    "",
    "You are hereby directed to explain the reasons for the above discrepancy or pay the tax",
    "along with applicable interest and penalty under Section 73 of the CGST Act.",
    "",
    "Please reply by: 30-Oct-2026.",
    "",
    "Failure to furnish a satisfactory explanation within the stipulated time may result in",
    "proceedings under Section 73 or 74 of the CGST Act, 2017.",
    "",
    "Yours faithfully,",
    "R.K. Sharma",
    "Superintendent of Central Tax",
    "Ward 42, Mumbai"
]

for line in content:
    text.textLine(line)

c.drawText(text)
c.save()
