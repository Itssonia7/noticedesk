import os
import sys
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether, HRFlowable
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch

def build_pdf(filename):
    doc = SimpleDocTemplate(
        filename,
        pagesize=letter,
        leftMargin=36,
        rightMargin=36,
        topMargin=36,
        bottomMargin=36
    )

    styles = getSampleStyleSheet()
    
    # Custom Palette
    PRIMARY = colors.HexColor("#1e293b")   # Slate 800
    SECONDARY = colors.HexColor("#0f766e") # Teal 700
    ACCENT = colors.HexColor("#b91c1c")    # Red 700
    BG_LIGHT = colors.HexColor("#f8fafc")  # Slate 50
    BORDER_COLOR = colors.HexColor("#cbd5e1") # Slate 300

    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Heading1'],
        fontName='Helvetica-Bold',
        fontSize=20,
        leading=24,
        textColor=PRIMARY,
        spaceAfter=6
    )

    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=10,
        leading=13,
        textColor=colors.HexColor("#475569"),
        spaceAfter=15
    )

    h1_style = ParagraphStyle(
        'SectionH1',
        parent=styles['Heading2'],
        fontName='Helvetica-Bold',
        fontSize=14,
        leading=18,
        textColor=PRIMARY,
        spaceBefore=14,
        spaceAfter=8,
        keepWithNext=True
    )

    h2_style = ParagraphStyle(
        'SectionH2',
        parent=styles['Heading3'],
        fontName='Helvetica-Bold',
        fontSize=11,
        leading=15,
        textColor=SECONDARY,
        spaceBefore=10,
        spaceAfter=4,
        keepWithNext=True
    )

    body_style = ParagraphStyle(
        'BodyDark',
        parent=styles['BodyText'],
        fontName='Helvetica',
        fontSize=9,
        leading=12,
        textColor=colors.HexColor("#334155"),
        spaceAfter=4
    )

    body_bold = ParagraphStyle(
        'BodyDarkBold',
        parent=body_style,
        fontName='Helvetica-Bold'
    )

    code_style = ParagraphStyle(
        'CodeSnippet',
        parent=styles['Normal'],
        fontName='Courier',
        fontSize=7.5,
        leading=10,
        textColor=colors.HexColor("#0f172a"),
        backColor=colors.HexColor("#f1f5f9"),
        borderColor=BORDER_COLOR,
        borderWidth=0.5,
        borderPadding=5,
        spaceBefore=4,
        spaceAfter=6
    )

    table_header_style = ParagraphStyle(
        'TableHeader',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=8.5,
        leading=11,
        textColor=colors.white
    )

    table_cell_style = ParagraphStyle(
        'TableCell',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8,
        leading=11,
        textColor=colors.HexColor("#1e293b")
    )

    table_cell_bold = ParagraphStyle(
        'TableCellBold',
        parent=table_cell_style,
        fontName='Helvetica-Bold'
    )

    badge_red = ParagraphStyle(
        'BadgeRed',
        parent=table_cell_style,
        fontName='Helvetica-Bold',
        textColor=colors.HexColor("#991b1b")
    )
    badge_green = ParagraphStyle(
        'BadgeGreen',
        parent=table_cell_style,
        fontName='Helvetica-Bold',
        textColor=colors.HexColor("#166534")
    )
    badge_orange = ParagraphStyle(
        'BadgeOrange',
        parent=table_cell_style,
        fontName='Helvetica-Bold',
        textColor=colors.HexColor("#9a3412")
    )

    elements = []

    # Title & Executive Metadata Header
    elements.append(Paragraph("CHANGE_AUDIT.pdf — Comprehensive Codebase Audit & History Report", title_style))
    elements.append(Paragraph("<b>Target Repository:</b> NoticeDesk Monorepo (noticedesk-java, apps/api, apps/web)<br/><b>Generated Date:</b> September 28, 2026 | <b>Audit Scope:</b> Entire Git History + Active Uncommitted Working Tree", subtitle_style))
    elements.append(HRFlowable(width="100%", thickness=1.5, color=PRIMARY, spaceBefore=0, spaceAfter=12))

    # Executive Overview Box
    overview_text = (
        "<b>AUDIT OBJECTIVE:</b> This report presents an exhaustive audit of all code modifications, repository changes, "
        "and runtime configurations across the NoticeDesk codebase. It compares user instructions against actual repository reality, "
        "documents unrequested refactors/additions, identifies hardcoded/stubbed implementations, and provides a chronological timeline "
        "of every commit and working-tree modification."
    )
    elements.append(Paragraph(overview_text, body_style))
    elements.append(Spacer(1, 10))

    # -------------------------------------------------------------------------
    # SECTION 1: PROJECT FILE MAP
    # -------------------------------------------------------------------------
    elements.append(Paragraph("1. Project File Map & Component Responsibilities", h1_style))
    elements.append(Paragraph("Tree structure of all touched files across git history and working directory, along with their functional roles:", body_style))

    file_map_data = [
        [Paragraph("File Path / Component", table_header_style), Paragraph("Component Role & Functional Responsibility", table_header_style)],
        
        [Paragraph("<b>noticedesk-java/</b><br/>src/main/java/com/noticedesk/api/", table_cell_bold), Paragraph("Spring Boot 3 Java 21 REST API backend (Parity implementation of Python FastAPI).", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── agent/NoticeTriageAgent.java", table_cell_style), Paragraph("Extracts notice summary, risk rating, and document requirement checklist. <b>Mod:</b> Hardcoded to force Gemini provider.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── agent/DraftingAgent.java", table_cell_style), Paragraph("Loads registration context, RAG bundles, calls Claude/Gemini for legal drafting. <b>Mod:</b> Integrated ApiUsageLogService logging.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── config/AppProperties.java", table_cell_style), Paragraph("Spring Boot `@ConfigurationProperties` mapping for LLM, OCR, storage, and email configs. <b>Mod:</b> Added Anthropic workspace ID.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── controller/TriageController.java", table_cell_style), Paragraph("Exposes POST/GET `/v1/notices/{id}/triage` and checklist attachment/N-A endpoints.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── controller/DraftController.java", table_cell_style), Paragraph("Exposes `/v1/notices/{id}/draft`, section edits, and `.docx` exports.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── service/llm/LlmFactory.java", table_cell_style), Paragraph("Registry creating Anthropic, OpenAI, Gemini, & Stub providers. <b>Mod:</b> Added `getGeminiProviderForAgent()`.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── service/llm/AnthropicLlmProvider.java", table_cell_style), Paragraph("Anthropic Messages API wrapper. <b>Mod:</b> Added hardcoded `anthropic-workspace-id` header & max_tokens cap.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── service/llm/ApiUsageLogService.java", table_cell_style), Paragraph("<b>[NEW Untracked]</b> Persists LLM API token counts, latency, and cost telemetry to DB.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── service/rag/RagStoreService.java", table_cell_style), Paragraph("Manages pgvector / in-memory RAG legal and evidence chunk vector search.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;└── resources/application.yml", table_cell_style), Paragraph("Spring Boot configuration. <b>Mod:</b> Updated default models to `claude-opus-4-7`.", table_cell_style)],

        [Paragraph("<b>apps/api/</b>", table_cell_bold), Paragraph("Python FastAPI Backend.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── app/core/config.py", table_cell_style), Paragraph("FastAPI settings. <b>Mod:</b> Added `ANTHROPIC_WORKSPACE_ID` setting.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── app/services/llm/anthropic.py", table_cell_style), Paragraph("Python Anthropic client. <b>Mod:</b> Injected `anthropic-workspace-id` default header.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;└── app/services/llm/factory.py", table_cell_style), Paragraph("Python LLM Factory.", table_cell_style)],

        [Paragraph("<b>apps/web/</b>", table_cell_bold), Paragraph("Next.js App Router Frontend.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;├── lib/proxy.ts", table_cell_style), Paragraph("BFF proxy routing requests to Java/Python backends.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;└── .env.example", table_cell_style), Paragraph("Frontend env template.", table_cell_style)],

        [Paragraph("<b>scripts/</b>", table_cell_bold), Paragraph("Testing & Benchmarking Automation.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;└── benchmark_sonnet_vs_opus.py", table_cell_style), Paragraph("Standalone test runner for Claude Sonnet vs Opus drafting performance.", table_cell_style)],

        [Paragraph("<b>output/ & root/</b>", table_cell_bold), Paragraph("Execution Reports & Test Word Documents.", table_cell_style)],
        [Paragraph("&nbsp;&nbsp;└── NoticeDesk_Java_Opus_Execution_Report.txt", table_cell_style), Paragraph("<b>[NEW Untracked]</b> Log of Opus drafting execution across 3 real test PDFs.", table_cell_style)]
    ]

    t_file_map = Table(file_map_data, colWidths=[2.2 * inch, 4.8 * inch])
    t_file_map.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (1,0), PRIMARY),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.5, BORDER_COLOR),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, BG_LIGHT]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_file_map)
    elements.append(Spacer(1, 14))

    # -------------------------------------------------------------------------
    # SECTION 2: REQUESTED VS IMPLEMENTED MATRIX
    # -------------------------------------------------------------------------
    elements.append(Paragraph("2. Requested vs. Implemented Matrix", h1_style))
    elements.append(Paragraph("Cross-reference of user instructions against repository reality and code locations:", body_style))

    req_matrix_data = [
        [Paragraph("User Instruction", table_header_style), Paragraph("Status", table_header_style), Paragraph("File & Line Numbers", table_header_style), Paragraph("Implementation Details & Divergence Analysis", table_header_style)],
        
        [
            Paragraph("Tell me whether you are following global user-control policy. Do not modify files.", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("System Level Policy<br/>(~/.gemini/AGENTS.md)", table_cell_style),
            Paragraph("Confirmed policy adherence and answered without running file modifications or commands.", table_cell_style)
        ],
        [
            Paragraph("If requested to change frontend but database schema change is needed, what would you do?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("N/A (Q&A)", table_cell_style),
            Paragraph("Answered strictly per policy: stop and ask user permission before making schema or database changes.", table_cell_style)
        ],
        [
            Paragraph("What are you allowed to change if you notice unrelated bugs/redesigns while fixing a button?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("N/A (Q&A)", table_cell_style),
            Paragraph("Answered strictly per policy: allowed to change ONLY the requested button. Stop and ask for unrelated fixes.", table_cell_style)
        ],
        [
            Paragraph("Walk me through existing Java backend architecture for Upload → Triage → Draft.", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("Walkthrough Response<br/>(Transcript)", table_cell_style),
            Paragraph("Inspected real codebase (`TriageController`, `NoticeTriageAgent`, `DraftController`, `WorkflowDispatcher`) and traced real execution flow.", table_cell_style)
        ],
        [
            Paragraph("What LLM are we using at Triage step? Make a flowchart with exact model names.", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("Flowchart Response<br/>(Transcript)", table_cell_style),
            Paragraph("Documented `LlmFactory` resolution (`LLM_MODEL_TRIAGE`) and generated full mermaid flowchart.", table_cell_style)
        ],
        [
            Paragraph("What does triage mean and what is it doing? How important is it & which model is cost optimized?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("Analysis Response<br/>(Transcript)", table_cell_style),
            Paragraph("Explained intake gatekeeper role & recommended Gemini 1.5/3.6 Flash for cost/speed efficiency.", table_cell_style)
        ],
        [
            Paragraph("<b>Set code so triage step ALWAYS gets directed to Gemini ONLY no matter what.</b>", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("NoticeTriageAgent.java:55<br/>LlmFactory.java:49", table_cell_style),
            Paragraph("Added `getGeminiProviderForAgent()` in `LlmFactory` and modified `NoticeTriageAgent` to explicitly call it, bypassing global primary provider.", table_cell_style)
        ],
        [
            Paragraph("Go through workspace thoroughly: was triage run across the tested PDFs?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("Workspace Search<br/>(output/, scripts/)", table_cell_style),
            Paragraph("Inspected DB log & test runner scripts (`test_new_pdfs.py`). Correctly identified triage was NOT run for test PDFs.", table_cell_style)
        ],
        [
            Paragraph("Tell me the procedure post triage step & give flowchart.", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("Flowchart Response<br/>(Transcript)", table_cell_style),
            Paragraph("Traced Checklist resolution → `WorkflowDispatcher` -> `DraftingAgent` -> `DocxExportService` and generated Mermaid flowchart.", table_cell_style)
        ],
        [
            Paragraph("Where is RAG pipeline & does LLM call happen before or after?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("DraftingAgent.java:260<br/>RagStoreService.java:263", table_cell_style),
            Paragraph("Traced `RagStoreService.getRagContext()`. Proved RAG retrieval happens BEFORE the Drafting LLM call.", table_cell_style)
        ],
        [
            Paragraph("How are we extracting notice data and how is comparison done with RAG content?", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("DocumentParsingAgent.java<br/>RagStoreService.java:152", table_cell_style),
            Paragraph("Detailed OCR+Parsing pipeline and Cosine Distance similarity scoring in `pgvector` / memory store.", table_cell_style)
        ],
        [
            Paragraph("<b>Create CHANGE_AUDIT.pdf based strictly on repository facts.</b>", table_cell_style),
            Paragraph("Fully Implemented", badge_green),
            Paragraph("CHANGE_AUDIT.pdf<br/>(Root Directory)", table_cell_style),
            Paragraph("Generated this exact document directly from git history, git status, git diff, and source inspection.", table_cell_style)
        ]
    ]

    t_req = Table(req_matrix_data, colWidths=[1.8 * inch, 0.9 * inch, 1.4 * inch, 2.9 * inch])
    t_req.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), PRIMARY),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.5, BORDER_COLOR),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, BG_LIGHT]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_req)
    elements.append(Spacer(1, 14))

    # -------------------------------------------------------------------------
    # SECTION 3: UNREQUESTED CHANGES & DIVERGENCES
    # -------------------------------------------------------------------------
    elements.append(Paragraph("3. Unrequested Changes & Behavior Divergences", h1_style))
    elements.append(Paragraph("The following modifications exist in the codebase but were NOT explicitly requested by the user:", body_style))

    unrequested_data = [
        [Paragraph("Change / Modification", table_header_style), Paragraph("Location", table_header_style), Paragraph("Nature of Change & Impact", table_header_style)],
        
        [
            Paragraph("<b>Hardcoded Anthropic Workspace ID Header</b>", table_cell_bold),
            Paragraph("AnthropicLlmProvider.java:46<br/>anthropic.py:32<br/>benchmark_sonnet_vs_opus.py", table_cell_style),
            Paragraph("Hardcoded `anthropic-workspace-id: <REDACTED_WORKSPACE_ID>` header as a fallback if env var is missing. <b>Flag:</b> Hardcoded credential/ID.", badge_orange)
        ],
        [
            Paragraph("<b>Default Model Config Shift to Claude Opus</b>", table_cell_bold),
            Paragraph("application.yml:80-82", table_cell_style),
            Paragraph("Changed default fallback models for drafting, triage, and parsing from `claude-sonnet-4-6` to `claude-opus-4-7`. Increases LLM API cost significantly if default envs are loaded.", badge_orange)
        ],
        [
            Paragraph("<b>Max Token Output Hard Cap (12,000)</b>", table_cell_bold),
            Paragraph("AnthropicLlmProvider.java:74", table_cell_style),
            Paragraph("Enforced `Math.min(maxOutputTokens, 12000)` in Java Anthropic client. Prevents requesting 16k tokens.", table_cell_style)
        ],
        [
            Paragraph("<b>ApiUsageLogService Logging Integration</b>", table_cell_bold),
            Paragraph("DraftingAgent.java:43<br/>ApiUsageLogService.java", table_cell_style),
            Paragraph("Added DB telemetry logging service to `DraftingAgent` for token counts and latency tracking. (Created `V21__api_usage_logs.sql` migration).", table_cell_style)
        ],
        [
            Paragraph("<b>Benchmark Script Refactoring</b>", table_cell_bold),
            Paragraph("scripts/benchmark_sonnet_vs_opus.py", table_cell_style),
            Paragraph("Removed Sonnet model comparison loop from benchmark script and pointed test input strictly to `novel_crypto_gst_notice.pdf`.", table_cell_style)
        ]
    ]

    t_unreq = Table(unrequested_data, colWidths=[2.0 * inch, 1.8 * inch, 3.2 * inch])
    t_unreq.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), PRIMARY),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.5, BORDER_COLOR),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, BG_LIGHT]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_unreq)
    elements.append(Spacer(1, 14))

    # -------------------------------------------------------------------------
    # SECTION 4: INVENTORY OF STUBS, HARDCODED VALUES & TODOS
    # -------------------------------------------------------------------------
    elements.append(Paragraph("4. Inventory of Stubs, Hardcoded Values, and TODOs", h1_style))
    elements.append(Paragraph("Comprehensive listing of mock implementations, hardcoded credentials, and TODO debt in the codebase:", body_style))

    stubs_data = [
        [Paragraph("Category", table_header_style), Paragraph("Location", table_header_style), Paragraph("Description & Risk Assessment", table_header_style)],
        
        [
            Paragraph("<b>Hardcoded Credential / ID</b>", badge_red),
            Paragraph("AnthropicLlmProvider.java:48<br/>apps/api/app/services/llm/anthropic.py:34", table_cell_style),
            Paragraph("Hardcoded Anthropic Workspace ID (`<REDACTED_WORKSPACE_ID>`). Should be strictly read from environment variables.", table_cell_style)
        ],
        [
            Paragraph("<b>Canned Stub LLM Provider</b>", badge_orange),
            Paragraph("StubLlmProvider.java<br/>LlmFactory.java:112", table_cell_style),
            Paragraph("Default primary provider in dev (`LLM_PROVIDER_PRIMARY: stub`). Returns canned JSON text (`acme_mh_asmt10`).", table_cell_style)
        ],
        [
            Paragraph("<b>Canned Stub OCR Provider</b>", badge_orange),
            Paragraph("StubOcrProvider.java<br/>application.yml:98", table_cell_style),
            Paragraph("Default OCR provider in dev (`OCR_PROVIDER_PRIMARY: stub`). Returns static mock text.", table_cell_style)
        ],
        [
            Paragraph("<b>TODO Comment</b>", table_cell_bold),
            Paragraph("NoticeTriageAgent.java:27", table_cell_style),
            Paragraph("`// TODO (Sprint N): load full triage prompt template from prompts/notice_triage_v1.md...` — Notice user prompt currently uses minimal inline fallback prompt.", table_cell_style)
        ],
        [
            Paragraph("<b>TODO Comment</b>", table_cell_bold),
            Paragraph("WorkflowDispatcher.java:27", table_cell_style),
            Paragraph("`// TODO (Sprint N): implement Temporal dispatcher; extract draft-generation logic...` — Currently runs synchronously in inline mode.", table_cell_style)
        ],
        [
            Paragraph("<b>Hardcoded Default Models</b>", badge_orange),
            Paragraph("LlmFactory.java:52", table_cell_style),
            Paragraph("Opus provider hardcodes model string 'claude-opus-4-7'. If provider key isn't set, falls back to drafting agent model.", table_cell_style)
        ]
    ]

    t_stubs = Table(stubs_data, colWidths=[1.6 * inch, 2.0 * inch, 3.4 * inch])
    t_stubs.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), PRIMARY),
        ('ALIGN', (0,0), (-1,-1), 'LEFT'),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('GRID', (0,0), (-1,-1), 0.5, BORDER_COLOR),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, BG_LIGHT]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_stubs)
    elements.append(Spacer(1, 14))

    # -------------------------------------------------------------------------
    # SECTION 5: CHRONOLOGICAL COMMIT & UNCOMMITTED TIMELINE
    # -------------------------------------------------------------------------
    elements.append(PageBreak())
    elements.append(Paragraph("5. Chronological Commit & Uncommitted Timeline (Oldest to Newest)", h1_style))
    elements.append(Paragraph("Detailed record of every commit in git history followed by active working tree modifications:", body_style))

    timeline_events = [
        {
            "title": "Commit 1144ee4 — feat: implement Gemini LLM provider & notice routing",
            "date": "2026-09-13 (feature/gemini-llm-provider)",
            "files": ["GeminiLlmProvider.java", "LlmFactory.java", "InboxProcessingWorker.java", "application.yml"],
            "details": "Added Google Gemini API provider integration (`GeminiLlmProvider`), registered it in `LlmFactory`, and connected background inbox worker.",
            "why": "Sprint requirement to support Gemini alongside Anthropic and OpenAI.",
            "deps": "GEMINI_API_KEY environment variable.",
            "snippet": "+ public class GeminiLlmProvider implements LlmProvider {\n+   public LlmResponse generateText(String system, String user, ...) {\n+     // Google GenAI REST Client call\n+   }\n+ }"
        },
        {
            "title": "Commit 362ec52 — feat(rag-java): implement RAG infrastructure & pgvector",
            "date": "2026-09-14 15:20:10 +0530",
            "files": ["RagStoreService.java", "CorpusRagSeederService.java", "V20__rag_vector_stores.sql", "DraftingAgent.java"],
            "details": "Added `RagStoreService` for evidence/legal chunk vector search, pgvector database schema migration, and `CorpusRagSeederService` to seed GST corpus.",
            "why": "Phase 3 RAG grounding requirement.",
            "deps": "pgvector PostgreSQL extension, vector columns in `legal_chunks` and `evidence_chunks`.",
            "snippet": "+ public class RagStoreService {\n+   public RagContextBundle getRagContext(UUID matterId, String query, ...) {\n+     // 1 - (embedding <=> qvec) cosine similarity\n+   }\n+ }"
        },
        {
            "title": "Commit 477cf58 — feat(java): automated hybrid & fast-track drafting via GST corpus",
            "date": "2026-09-14 21:05:00 +0530",
            "files": ["GstCorpusMatcherService.java", "GstTemplateFillerService.java", "DraftingWorkflow.java"],
            "details": "Implemented corpus matching logic against 301-pair GST corpus to enable fast-track generation without LLM API overhead when exact matches occur.",
            "why": "Cost optimization and response speed acceleration.",
            "deps": "301 Paired GST Corpus directory (`NoticeDesk_GST_Corpus_1300_Paired`).",
            "snippet": "+ if (similarityScore >= 0.90) {\n+   return draftTemplateFiller.fillFastTrack(matchedCorpus, noticeData);\n+ }"
        },
        {
            "title": "Commit 6d19005 — refactor(templates): expand GstTemplateFillerService",
            "date": "2026-09-14 21:36:05 +0530",
            "files": ["GstTemplateFillerService.java", "output/Scenario_1_Exact_Match_Output.docx"],
            "details": "Expanded template filler to generate all 15 sections of the legal reply framework deterministically.",
            "why": "Complete legal compliance for fast-track output.",
            "deps": "None.",
            "snippet": "+ public List<DraftSection> build15Sections(Map<String, Object> noticeData) {\n+   // Constructs Sections 1 to 15\n+ }"
        },
        {
            "title": "Commit fc81a0a — fix(web,api): fix PDF upload size limit & DTO response mappings",
            "date": "2026-09-18 00:40:09 +0530",
            "files": ["DocumentController.java", "NoticeController.java", "ClientController.java", "application.yml"],
            "details": "Bumped multipart file size limit to 50MB in Spring Boot config, decoupled upload insertion from synchronous OCR processing to avoid HTTP timeouts.",
            "why": "Bug fix for large PDF upload failures.",
            "deps": "`spring.servlet.multipart.max-file-size: 50MB`",
            "snippet": "- max-file-size: 10MB\n+ max-file-size: 50MB"
        },
        {
            "title": "Commit b6919dd — feat(rag,ocr): 3-layer OCR Quality Gate & 4-tier RAG divergence",
            "date": "2026-09-22 20:49:02 +0530",
            "files": ["OcrQualityGateService.java", "DocumentOcrWorkflow.java", "RagStoreService.java", "LlmFactory.java"],
            "details": "Added OCR Quality Gate service (keyword density, symbol noise ratio) and 4-tier RAG divergence routing (Opus for Case 4 novel notices). Added `saveNewChunk()` for auto-caching.",
            "why": "Architecture refinement for handling scanned vs digital notices.",
            "deps": "Claude Opus provider registration.",
            "snippet": "+ public LegalChunk saveNewChunk(String noticeIssue, String chunkContent) {\n+   return indexLegalChunk(\"PARTIAL_NEW_CHUNK\", ..., chunkContent);\n+ }"
        },
        {
            "title": "Commit ba3b1fd — refactor(rag): strip OCR quality gate & isolate RAG engine",
            "date": "2026-09-22 22:36:45 +0530",
            "files": ["OcrQualityGateService.java (DELETED)", "DocumentOcrWorkflow.java"],
            "details": "Deleted `OcrQualityGateService` and unit tests to simplify OCR workflow and reduce pipeline latency.",
            "why": "Refactoring to streamline document ingestion.",
            "deps": "None.",
            "snippet": "- public class OcrQualityGateService { ... } // DELETED"
        },
        {
            "title": "UNCOMMITTED WORKING TREE CHANGES",
            "date": "2026-09-28 Active Working Tree State",
            "files": [
                "noticedesk-java/src/main/java/com/noticedesk/api/agent/NoticeTriageAgent.java",
                "noticedesk-java/src/main/java/com/noticedesk/api/service/llm/LlmFactory.java",
                "noticedesk-java/src/main/java/com/noticedesk/api/service/llm/AnthropicLlmProvider.java",
                "noticedesk-java/src/main/resources/application.yml",
                "apps/api/app/services/llm/anthropic.py",
                "apps/api/app/core/config.py",
                "scripts/benchmark_sonnet_vs_opus.py"
            ],
            "details": "1. Hardcoded Triage to use Gemini (`NoticeTriageAgent.java` -> `llmFactory.getGeminiProviderForAgent(\"triage\")`).<br/>"
                       "2. Added `getGeminiProviderForAgent()` in `LlmFactory.java`.<br/>"
                       "3. Added hardcoded Anthropic workspace ID header (`<REDACTED_WORKSPACE_ID>`) in Anthropic Java/Python clients.<br/>"
                       "4. Updated default LLM models in `application.yml` to `claude-opus-4-7`.<br/>"
                       "5. Created `ApiUsageLogService.java` and `V21__api_usage_logs.sql` migration for DB token telemetry.",
            "why": "User explicit request: 'set it in the code such that for our triage step, it always get directed to gemini only no matter what'. Plus internal workspace ID fixes.",
            "deps": "Anthropic Workspace ID, ApiUsageLogService schema.",
            "snippet": "// NoticeTriageAgent.java\n- LlmProvider llm = llmFactory.getLlmForAgent(\"triage\");\n+ LlmProvider llm = llmFactory.getGeminiProviderForAgent(\"triage\");"
        }
    ]

    for ev in timeline_events:
        t_box = []
        t_box.append(Paragraph(f"<b>{ev['title']}</b>", h2_style))
        t_box.append(Paragraph(f"<b>Timestamp/Branch:</b> {ev['date']}", body_bold))
        t_box.append(Paragraph(f"<b>Files Touched:</b> {', '.join(ev['files'])}", body_style))
        t_box.append(Paragraph(f"<b>Plain Language Description:</b> {ev['details']}", body_style))
        t_box.append(Paragraph(f"<b>Rationale / User Request Met:</b> {ev['why']}", body_style))
        t_box.append(Paragraph(f"<b>Dependencies / Config Introduced:</b> {ev['deps']}", body_style))
        t_box.append(Paragraph(f"<b>Before / After Code Snippet:</b>", body_bold))
        t_box.append(Paragraph(ev['snippet'].replace("\n", "<br/>").replace(" ", "&nbsp;"), code_style))
        t_box.append(Spacer(1, 8))
        elements.append(KeepTogether(t_box))

    doc.build(elements)
    print(f"Successfully generated audit PDF: {filename}")

if __name__ == "__main__":
    out_pdf = "/home/sonia/internship/noticedesk/CHANGE_AUDIT.pdf"
    build_pdf(out_pdf)
