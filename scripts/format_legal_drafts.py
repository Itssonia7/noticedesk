#!/usr/bin/env python3
"""
Legal Document Formatter: Converts raw LLM legal reply drafts into formal,
professionally styled Indian Legal Pleading documents (.docx and .pdf).
"""

import os
import re
import sys
import zipfile
import subprocess
import xml.etree.ElementTree as ET

import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import OxmlElement, parse_xml
from docx.oxml.ns import nsdecls, qn

from fpdf import FPDF


def extract_raw_text(filepath):
    """Extract raw text from PDF or DOCX file."""
    if filepath.endswith('.docx'):
        with zipfile.ZipFile(filepath) as z:
            xml_content = z.read('word/document.xml')
        root = ET.fromstring(xml_content)
        text = []
        for elem in root.iter():
            if elem.tag.endswith('}t') and elem.text:
                text.append(elem.text)
            elif elem.tag.endswith('}p'):
                text.append('\n')
        return ''.join(text)
    else:
        res = subprocess.run(['pdftotext', filepath, '-'], stdout=subprocess.PIPE, text=True)
        return res.stdout


def clean_legal_text(raw_text):
    """
    Strips raw LLM metadata headers, markdown symbols, and divider lines.
    """
    lines = raw_text.split('\n')
    cleaned_lines = []
    
    in_meta_header = True
    for line in lines:
        stripped = line.strip()
        
        # Skip top banner/meta headers
        if in_meta_header:
            if any(stripped.startswith(prefix) for prefix in [
                'Claude Opus', 'Claude Sonnet', 'NOTICEDESK EXHAUSTIVE',
                'Title:', 'Source PDF:', '==='
            ]) or (stripped.startswith('=') and len(stripped) > 5):
                continue
            if stripped == '' or stripped.startswith('==='):
                continue
            # When we reach actual content, exit meta header state
            in_meta_header = False
            
        # Skip ASCII horizontal divider lines
        if re.match(r'^[\=\-\_]{5,}$', stripped):
            continue
            
        cleaned_lines.append(line)
        
    return '\n'.join(cleaned_lines)


def parse_document_blocks(text):
    """
    Parses cleaned text into structured blocks:
    - header
    - cause_title
    - document_title
    - preamble ('MOST RESPECTFULLY SHOWETH:')
    - paragraphs / sections
    - tables (parsed from ASCII table lines)
    - verification
    - signature / enclosures
    """
    lines = text.split('\n')
    blocks = []
    
    i = 0
    in_ascii_table = False
    table_lines = []
    
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Detect ASCII table lines (+---+ or | ... |)
        if re.match(r'^\+[\-\+]+\+$', stripped) or (stripped.startswith('|') and stripped.endswith('|')):
            in_ascii_table = True
            table_lines.append(stripped)
            i += 1
            continue
        elif in_ascii_table:
            # End of ASCII table
            in_ascii_table = False
            blocks.append({'type': 'table', 'content': parse_ascii_table(table_lines)})
            table_lines = []
            
        if not stripped:
            i += 1
            continue
            
        # Clean markdown bold/italics symbols
        clean_text = re.sub(r'\*\*(.*?)\*\*', r'\1', stripped)
        clean_text = re.sub(r'\*(.*?)\*', r'\1', clean_text)
        
        # Classification of block types
        if any(h in clean_text.upper() for h in ['BEFORE THE', 'IN THE COURT OF', 'BEFORE THE PROPER OFFICER']):
            blocks.append({'type': 'jurisdiction_header', 'content': clean_text})
        elif any(t in clean_text.upper() for t in ['REPLY TO', 'WRITTEN SUBMISSION', 'WRITTEN REPLY', 'COMPREHENSIVE POINT-WISE', 'MEMORANDUM OF']) and len(clean_text) < 150:
            blocks.append({'type': 'document_title', 'content': clean_text})
        elif 'MOST RESPECTFULLY SHOWETH' in clean_text.upper():
            blocks.append({'type': 'preamble', 'content': 'MOST RESPECTFULLY SHOWETH:'})
        elif clean_text.upper().startswith('VERIFICATION'):
            blocks.append({'type': 'section_header', 'content': 'VERIFICATION'})
        elif re.match(r'^(I{1,3}|IV|V|VI|VII|VIII|IX|X|\d+)\.\s+[A-Z\s\&\-\,]{4,}$', clean_text):
            blocks.append({'type': 'section_header', 'content': clean_text})
        elif re.match(r'^\d+(\.\d+)*[\.\)]\s+', clean_text) or re.match(r'^\([a-z0-9]+\)\s+', clean_text):
            blocks.append({'type': 'numbered_para', 'content': clean_text})
        elif clean_text.startswith('PRAYER') or clean_text.startswith('RELIEF CLAUSE'):
            blocks.append({'type': 'section_header', 'content': clean_text})
        elif clean_text.startswith('LIST OF ENCLOSURES') or clean_text.startswith('ANNEXURES'):
            blocks.append({'type': 'section_header', 'content': clean_text})
        elif 'Authorized Signatory' in clean_text or 'Proprietor' in clean_text or clean_text.startswith('For M/s'):
            blocks.append({'type': 'signature', 'content': clean_text})
        else:
            blocks.append({'type': 'body', 'content': clean_text})
            
        i += 1
        
    if in_ascii_table and table_lines:
        blocks.append({'type': 'table', 'content': parse_ascii_table(table_lines)})
        
    return blocks


def parse_ascii_table(table_lines):
    """Converts ASCII table lines into a list of row lists."""
    rows = []
    for line in table_lines:
        if line.startswith('+'):
            continue
        cells = [c.strip() for c in line.split('|')[1:-1]]
        if cells:
            rows.append(cells)
    return rows


def set_cell_background(cell, fill_hex):
    """Sets table cell background color in docx."""
    shading_elm = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>')
    cell._tc.get_or_add_tcPr().append(shading_elm)


def set_cell_margins(cell, top=100, bottom=100, left=150, right=150):
    """Sets table cell margins in docx."""
    tcPr = cell._tc.get_or_add_tcPr()
    tcMar = parse_xml(f'<w:tcMar {nsdecls("w")}><w:top w:w="{top}" w:type="dxa"/><w:bottom w:w="{bottom}" w:type="dxa"/><w:left w:w="{left}" w:type="dxa"/><w:right w:w="{right}" w:type="dxa"/></w:tcMar>')
    tcPr.append(tcMar)


def build_docx(blocks, output_path):
    """Creates a beautifully styled Indian Legal Pleading Word (.docx) file."""
    doc = docx.Document()
    
    # 1-inch margins
    sections = doc.sections
    for section in sections:
        section.top_margin = Inches(1.0)
        section.bottom_margin = Inches(1.0)
        section.left_margin = Inches(1.0)
        section.right_margin = Inches(1.0)
        
    normal_style = doc.styles['Normal']
    normal_style.font.name = 'Times New Roman'
    normal_style.font.size = Pt(12)
    normal_style.font.color.rgb = RGBColor(0x11, 0x11, 0x11)
    
    # Default paragraph spacing
    normal_style.paragraph_format.line_spacing = 1.35
    normal_style.paragraph_format.space_after = Pt(6)
    normal_style.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    
    has_verification = any(b['type'] == 'section_header' and 'VERIFICATION' in b['content'] for b in blocks)
    firm_name = ""
    for b in blocks:
        if 'M/s' in b['content'] and ('Applicant' in b['content'] or 'Taxpayer' in b['content'] or 'Noticee' in b['content'] or 'Firm' in b['content'] or 'J R K' in b['content'] or 'PERFECT BUILDCON' in b['content']):
            m = re.search(r'M/s\s+[A-Za-z0-9\s]+', b['content'])
            if m:
                firm_name = m.group(0).strip()
                break
    if not firm_name:
        firm_name = "M/s TAXPAYER"

    for b in blocks:
        b_type = b['type']
        content = b['content']
        
        if b_type == 'jurisdiction_header':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.space_after = Pt(4)
            p.paragraph_format.line_spacing = 1.15
            run = p.add_run(content)
            run.font.name = 'Times New Roman'
            run.font.size = Pt(12)
            run.font.bold = True
            
        elif b_type == 'document_title':
            doc.add_paragraph() # Spacer
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.space_before = Pt(8)
            p.paragraph_format.space_after = Pt(12)
            run = p.add_run(content.upper())
            run.font.name = 'Times New Roman'
            run.font.size = Pt(13)
            run.font.bold = True
            run.font.underline = True
            doc.add_paragraph() # Spacer
            
        elif b_type == 'preamble':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.space_before = Pt(12)
            p.paragraph_format.space_after = Pt(10)
            run = p.add_run(content)
            run.font.name = 'Times New Roman'
            run.font.size = Pt(12)
            run.font.bold = True

        elif b_type == 'section_header':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.space_before = Pt(14)
            p.paragraph_format.space_after = Pt(6)
            run = p.add_run(content)
            run.font.name = 'Times New Roman'
            run.font.size = Pt(12)
            run.font.bold = True
            run.font.underline = True

        elif b_type == 'numbered_para':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            p.paragraph_format.space_after = Pt(6)
            p.paragraph_format.line_spacing = 1.35
            
            # Format number part as bold if starting with digits/letters
            m = re.match(r'^(\d+(\.\d+)*[\.\)]|\([a-z0-9]+\))\s+(.*)$', content)
            if m:
                num_part, _, rest_part = m.groups()
                run_num = p.add_run(num_part + " ")
                run_num.font.name = 'Times New Roman'
                run_num.font.bold = True
                run_txt = p.add_run(rest_part)
                run_txt.font.name = 'Times New Roman'
            else:
                run = p.add_run(content)
                run.font.name = 'Times New Roman'

        elif b_type == 'body':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            p.paragraph_format.space_after = Pt(6)
            p.paragraph_format.line_spacing = 1.35
            run = p.add_run(content)
            run.font.name = 'Times New Roman'

        elif b_type == 'table':
            table_data = content
            if not table_data:
                continue
                
            cols_count = max(len(row) for row in table_data)
            table = doc.add_table(rows=len(table_data), cols=cols_count)
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            
            for r_idx, row in enumerate(table_data):
                tr = table.rows[r_idx]
                for c_idx, val in enumerate(row):
                    cell = tr.cells[c_idx]
                    cell.text = val
                    set_cell_margins(cell, top=100, bottom=100, left=150, right=150)
                    
                    p = cell.paragraphs[0]
                    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
                    p.paragraph_format.space_after = Pt(2)
                    
                    run = p.runs[0] if p.runs else p.add_run(val)
                    run.font.name = 'Times New Roman'
                    run.font.size = Pt(10)
                    
                    if r_idx == 0:
                        set_cell_background(cell, "EAEAEA")
                        run.font.bold = True
                        
            doc.add_paragraph() # Spacer after table

        elif b_type == 'signature':
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
            p.paragraph_format.space_after = Pt(4)
            run = p.add_run(content)
            run.font.name = 'Times New Roman'
            run.font.bold = True

    # Add Formal Legal Verification Block if not present
    if not has_verification:
        doc.add_paragraph()
        p_vhdr = doc.add_paragraph()
        p_vhdr.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p_vhdr.paragraph_format.space_before = Pt(16)
        p_vhdr.paragraph_format.space_after = Pt(8)
        run_vh = p_vhdr.add_run("VERIFICATION")
        run_vh.font.name = 'Times New Roman'
        run_vh.font.size = Pt(12)
        run_vh.font.bold = True
        run_vh.font.underline = True

        p_vtxt = doc.add_paragraph()
        p_vtxt.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        p_vtxt.paragraph_format.space_after = Pt(12)
        vtext = f"I, the Authorized Signatory / Partner of {firm_name}, do hereby verify and declare that the contents of the above written submission are true and correct to the best of my knowledge, information, and belief derived from official business records. Nothing material has been concealed therefrom."
        run_vt = p_vtxt.add_run(vtext)
        run_vt.font.name = 'Times New Roman'
        run_vt.font.size = Pt(11)

        p_sig = doc.add_paragraph()
        p_sig.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        p_sig.paragraph_format.space_before = Pt(18)
        run_sig = p_sig.add_run(f"For {firm_name}\n\n___________________________\n(Authorized Signatory / Partner)")
        run_sig.font.name = 'Times New Roman'
        run_sig.font.bold = True

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    doc.save(output_path)
    print(f"  ✓ Saved DOCX: {output_path}")


class LegalPDF(FPDF):
    def header(self):
        self.set_font('Times', 'I', 8)
        self.set_text_color(120, 120, 120)
        self.cell(0, 5, 'FORMAL LEGAL PLEADING DRAFT -- PROCEEDING BEFORE TAX AUTHORITIES', 0, 1, 'R')
        self.ln(2)

    def footer(self):
        self.set_y(-15)
        self.set_font('Times', 'I', 9)
        self.set_text_color(120, 120, 120)
        self.cell(0, 10, f'Page {self.page_no()}', 0, 0, 'C')


def sanitize_latin1(text):
    if not text:
        return ""
    replacements = {
        '\u2013': '-',
        '\u2014': '--',
        '\u2018': "'",
        '\u2019': "'",
        '\u201c': '"',
        '\u201d': '"',
        '\u2022': '*',
        '\u2026': '...',
        '\u20b9': 'Rs.',
        '₹': 'Rs.',
        '§': 'Sec.'
    }
    for k, v in replacements.items():
        text = text.replace(k, v)
    return text.encode('latin-1', 'replace').decode('latin-1')


def build_pdf(blocks, output_path):
    """Creates a cleanly styled PDF file for the legal pleading."""
    pdf = LegalPDF(orientation='P', unit='mm', format='A4')
    pdf.set_margins(20, 20, 20)
    pdf.set_auto_page_break(auto=True, margin=20)
    pdf.add_page()
    
    firm_name = ""
    for b in blocks:
        if 'M/s' in b['content']:
            m = re.search(r'M/s\s+[A-Za-z0-9\s]+', b['content'])
            if m:
                firm_name = m.group(0).strip()
                break
    if not firm_name:
        firm_name = "M/s TAXPAYER"

    has_verification = any(b['type'] == 'section_header' and 'VERIFICATION' in b['content'] for b in blocks)

    for b in blocks:
        b_type = b['type']
        if isinstance(b['content'], str):
            content = sanitize_latin1(b['content'])
        else:
            content = b['content']

        if b_type == 'jurisdiction_header':
            pdf.set_font('Times', 'B', 11)
            pdf.set_text_color(0, 0, 0)
            pdf.multi_cell(0, 5, content, 0, 'C')
            pdf.ln(2)

        elif b_type == 'document_title':
            pdf.ln(3)
            pdf.set_font('Times', 'BU', 12)
            pdf.set_text_color(0, 0, 0)
            pdf.multi_cell(0, 6, content.upper(), 0, 'C')
            pdf.ln(4)

        elif b_type == 'preamble':
            pdf.ln(2)
            pdf.set_font('Times', 'B', 11)
            pdf.set_text_color(0, 0, 0)
            pdf.cell(0, 6, content, 0, 1, 'L')
            pdf.ln(2)

        elif b_type == 'section_header':
            pdf.ln(3)
            pdf.set_font('Times', 'BU', 11)
            pdf.set_text_color(0, 0, 0)
            pdf.multi_cell(0, 6, content, 0, 'L')
            pdf.ln(2)

        elif b_type in ('numbered_para', 'body'):
            pdf.set_font('Times', '', 11)
            pdf.set_text_color(20, 20, 20)
            pdf.multi_cell(0, 5.5, content, 0, 'J')
            pdf.ln(2)

        elif b_type == 'table':
            table_data = b['content']
            if not table_data:
                continue
            pdf.ln(2)
            cols_count = max(len(row) for row in table_data)
            col_width = 170.0 / cols_count
            
            for r_idx, row in enumerate(table_data):
                if r_idx == 0:
                    pdf.set_font('Times', 'B', 9)
                    pdf.set_fill_color(234, 234, 234)
                else:
                    pdf.set_font('Times', '', 9)
                    pdf.set_fill_color(255, 255, 255)
                    
                for c_idx in range(cols_count):
                    cell_val = row[c_idx] if c_idx < len(row) else ""
                    cell_clean = sanitize_latin1(cell_val)
                    pdf.cell(col_width, 6, cell_clean[:30], 1, 0, 'L', fill=True)
                pdf.ln()
            pdf.ln(3)

        elif b_type == 'signature':
            pdf.ln(4)
            pdf.set_font('Times', 'B', 11)
            pdf.multi_cell(0, 5, content, 0, 'R')

    if not has_verification:
        pdf.ln(5)
        pdf.set_font('Times', 'BU', 11)
        pdf.cell(0, 6, "VERIFICATION", 0, 1, 'C')
        pdf.ln(2)
        pdf.set_font('Times', '', 10)
        vtext = f"I, the Authorized Signatory / Partner of {firm_name}, do hereby verify and declare that the contents of the above written submission are true and correct to the best of my knowledge, information, and belief derived from official business records. Nothing material has been concealed therefrom."
        pdf.multi_cell(0, 5, sanitize_latin1(vtext), 0, 'J')
        pdf.ln(6)
        pdf.set_font('Times', 'B', 11)
        pdf.multi_cell(0, 5, sanitize_latin1(f"For {firm_name}\n\n___________________________\n(Authorized Signatory / Partner)"), 0, 'R')

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    pdf.output(output_path)
    print(f"  ✓ Saved PDF:  {output_path}")



def process_all_files():
    input_files = [
        ('comparision/j rk infrastructure/opus analysis.pdf', 'formating/j rk infrastructure/opus_analysis_formatted'),
        ('comparision/j rk infrastructure/sonnet analysis.pdf', 'formating/j rk infrastructure/sonnet_analysis_formatted'),
        ('comparision/j rk infrastructure/rag.docx', 'formating/j rk infrastructure/rag_formatted'),
        ('comparision/perfect buildcon/opus.pdf', 'formating/perfect buildcon/opus_formatted'),
        ('comparision/perfect buildcon/sonnet.pdf', 'formating/perfect buildcon/sonnet_formatted'),
        ('comparision/perfect buildcon/rag.docx', 'formating/perfect buildcon/rag_formatted')
    ]

    print("=== Processing & Formatting Legal Reply Drafts ===")

    for in_file, out_base in input_files:
        if not os.path.exists(in_file):
            print(f"Warning: File {in_file} not found. Skipping.")
            continue
            
        print(f"\nFormatting: {in_file}")
        raw_text = extract_raw_text(in_file)
        clean_text = clean_legal_text(raw_text)
        blocks = parse_document_blocks(clean_text)
        
        docx_path = out_base + '.docx'
        pdf_path = out_base + '.pdf'
        
        build_docx(blocks, docx_path)
        build_pdf(blocks, pdf_path)
        
        # Also copy to comparision/formating/ for convenience
        comp_out_base = out_base.replace('formating/', 'comparision/formating/')
        comp_docx_path = comp_out_base + '.docx'
        comp_pdf_path = comp_out_base + '.pdf'
        
        build_docx(blocks, comp_docx_path)
        build_pdf(blocks, comp_pdf_path)

    print("\n=== All Legal Reply Drafts Formatted Successfully ===")

if __name__ == '__main__':
    process_all_files()
