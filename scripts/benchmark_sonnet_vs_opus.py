#!/usr/bin/env python3
"""
Benchmark Script: Claude 3.5 Sonnet vs Claude 3 Opus
Evaluates legal drafting quality, latency, token usage, and cost on real GST notices.
"""

import os
import sys
import time
import json
import urllib.request
import subprocess

REAL_NOTICES = [
    {
        "filename": "./claude testing /AA271124111508J_SCN20122024.pdf",
        "title": "Form GST REG-03 Registration Clarification Notice (Perfect Buildcon)",
        "type": "digital"
    },
    {
        "filename": "./claude testing /GST NOTICE 24052024.pdf",
        "title": "CGST Audit Selection Notice FY 2020-23 (M/s J R K Infrastructure)",
        "type": "scanned"
    }
]

def extract_pdf_text(filepath, is_scanned=False):
    """Extract text using pdftotext or tesseract OCR."""
    if not is_scanned:
        cmd = ["pdftotext", filepath, "-"]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0 and len(res.stdout.strip()) > 50:
            return res.stdout
    
    # OCR using pdftoppm + tesseract
    print(f"Running tesseract OCR on {filepath}...")
    tmp_prefix = "/tmp/benchmark_page"
    subprocess.run(["pdftoppm", "-png", "-r", "150", "-f", "1", "-l", "2", filepath, tmp_prefix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    full_text = ""
    for i in range(1, 3):
        png_path = f"{tmp_prefix}-{i}.png"
        if os.path.exists(png_path):
            ocr_res = subprocess.run(["tesseract", png_path, "stdout"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            full_text += f"\n--- Page {i} ---\n" + ocr_res.stdout
            os.remove(png_path)
    return full_text

from format_legal_drafts import clean_legal_text, parse_document_blocks, build_docx, build_pdf

MASTER_PROMPT = """You are a Senior GST Advocate. Draft a comprehensive, formal, and legally robust written reply to the following GST Notice.

---
NOTICE TEXT:
{notice_text}
---

DRAFTING REQUIREMENTS:
1. HEADER & FORMAL ADDRESS: Address the Proper Officer with proper designation, office address, and jurisdiction.
2. CAUSE TITLE & PREAMBLE: Include formal Cause Title and 'MOST RESPECTFULLY SHOWETH:'.
3. POINT-BY-POINT LEGAL REBUTTAL:
   - Ground A: Address the primary discrepancy or documentation requirement citing CGST Act 2017 & relevant CBIC circulars.
   - Ground B: Challenge any premature penalty or procedural gap under Section 73/74/75.
4. PRAYER / RELIEF CLAUSE: Formally request dropping the proposed demand and requesting a personal hearing under Section 75(4).
5. VERIFICATION & ANNEXURES: Include formal Verification statement by the taxpayer and bulleted list of supporting documents attached.

FORMATTING RULES:
- Do NOT use markdown symbols like '#', '##', '***', or '---'.
- Do NOT output ASCII grid tables with '+---+'. Output standard text paragraphs or clean tabbed rows.
- Draft in professional Indian legal pleading format ready for official filing.
"""

def call_anthropic_complete(model_id, prompt, api_key, workspace_id):
    url = "https://api.anthropic.com/v1/messages"
    headers = {
        "x-api-key": api_key,
        "anthropic-version": "2023-06-01",
        "anthropic-workspace-id": workspace_id,
        "content-type": "application/json"
    }
    
    messages = [{"role": "user", "content": prompt}]
    full_response_text = ""
    total_in_tokens = 0
    total_out_tokens = 0
    start_time = time.time()
    
    for turn in range(3):
        payload = {
            "model": model_id,
            "max_tokens": 4096,
            "messages": messages
        }
        req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), headers=headers)
        
        try:
            with urllib.request.urlopen(req) as response:
                res = json.loads(response.read().decode('utf-8'))
                usage = res.get("usage", {})
                total_in_tokens += usage.get("input_tokens", 0)
                total_out_tokens += usage.get("output_tokens", 0)
                
                turn_text = ""
                for block in res.get("content", []):
                    if block.get("type") == "text":
                        turn_text += block.get("text", "")
                
                full_response_text += turn_text
                
                stop_reason = res.get("stop_reason")
                if stop_reason == "end_turn":
                    break
                elif stop_reason == "max_tokens":
                    messages.append({"role": "assistant", "content": turn_text})
                    messages.append({"role": "user", "content": "Please continue exactly where you left off until the Prayer Clause and Annexure Checklist are fully completed."})
                else:
                    break
        except Exception as e:
            err_msg = str(e)
            if hasattr(e, 'read'):
                err_msg += " " + e.read().decode('utf-8')
            return {"success": False, "error": err_msg}
            
    elapsed = time.time() - start_time
    return {
        "success": True,
        "text": full_response_text,
        "latency_s": round(elapsed, 2),
        "input_tokens": total_in_tokens,
        "output_tokens": total_out_tokens
    }

def export_legal_pleading(raw_text, docx_path, pdf_path):
    """Converts LLM output into formal legal pleading .docx and .pdf files."""
    cleaned_text = clean_legal_text(raw_text)
    blocks = parse_document_blocks(cleaned_text)
    build_docx(blocks, docx_path)
    build_pdf(blocks, pdf_path)

def main():
    api_key = open("apps/api/.env").read().strip().split("=")[1].strip()
    workspace_id = "wrkspc_014N4cnyTQiXVFyARaUjgt45"
    
    print(f"=== Running Full Untruncated Benchmark (max_tokens: 4096 + Continuation) ===")
    
    for idx, notice in enumerate(REAL_NOTICES):
        print(f"\n=======================================================")
        print(f"Notice #{idx+1}: {notice['title']}")
        print(f"=======================================================")
        extracted_text = extract_pdf_text(notice["filename"], notice["type"] == "scanned")
        prompt = MASTER_PROMPT.format(notice_text=extracted_text[:3500])
        
        models = [
            ("Claude Sonnet", "claude-sonnet-5"),
            ("Claude Opus", "claude-opus-4-7")
        ]
        
        for name, model_id in models:
            print(f"\nRunning {name} ({model_id})...")
            res = call_anthropic_complete(model_id, prompt, api_key, workspace_id)
            if res["success"]:
                print(f"  ✓ {name} completed in {res['latency_s']}s (Tokens: in={res['input_tokens']}, out={res['output_tokens']})")
                safe_title = notice['title'].split()[0].lower()
                docx_file = f"/home/sonia/internship/noticedesk/formating/benchmark_{safe_title}_{name.replace(' ', '_').lower()}.docx"
                pdf_file = f"/home/sonia/internship/noticedesk/formating/benchmark_{safe_title}_{name.replace(' ', '_').lower()}.pdf"
                
                export_legal_pleading(res["text"], docx_file, pdf_file)
                print(f"  Saved legal pleading draft to:\n    DOCX: {docx_file}\n    PDF:  {pdf_file}")
            else:
                print(f"  X {name} failed: {res['error']}")
                
    print("\n=== Untruncated Benchmark Execution Complete ===")

if __name__ == "__main__":
    main()
