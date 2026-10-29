package com.noticedesk.api.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic LLM stub for tests and local dev.
 *
 * <p>When {@code cannedKey} is provided (from {@code noticedesk.llm.stub-default-canned} /
 * {@code STUB_DEFAULT_CANNED} env var), each call loads
 * {@code classpath:stub-canned/{cannedKey}.json} and returns it verbatim.
 *
 * <p>Without a canned key, the stub detects the agent from system-prompt keywords:
 * <ul>
 *   <li>{@code document_type}, {@code parse}, {@code ocr} → parsing response</li>
 *   <li>{@code sections}, {@code draft}, {@code reply}    → drafting response</li>
 *   <li>{@code triage}, {@code checklist}                 → triage response</li>
 *   <li>anything else                                     → {@code {"status":"ok"}}</li>
 * </ul>
 */
@Slf4j
public class StubLlmProvider implements LlmProvider {

    private static final String PARSE_RESPONSE = """
            {
              "document_type": "needs_review",
              "law": null,
              "client_name_on_document": null,
              "pans_extracted": [],
              "gstins_extracted": [],
              "notice_number": null,
              "din_or_rfn": null,
              "issue_date": null,
              "receipt_date": null,
              "due_date": null,
              "hearing_date": null,
              "financial_year": null,
              "assessment_year": null,
              "authority": null,
              "demand_amount": null,
              "issues": [],
              "documents_required": [],
              "parse_confidence": 0.0,
              "fields_needing_review": ["document_type"]
            }
            """;

    private static final String DRAFT_RESPONSE = """
            {
              "sections": [
                {"num": 1,  "title": "Addressee and Reference Block", "body_html": "<p>Stub draft — Section 1.</p>"},
                {"num": 2,  "title": "Subject",                      "body_html": "<p>Stub draft — Section 2.</p>"},
                {"num": 3,  "title": "Synopsis",                     "body_html": "<p>Stub draft — Section 3.</p>"},
                {"num": 4,  "title": "Statement of Facts",           "body_html": "<p>Stub draft — Section 4.</p>"},
                {"num": 5,  "title": "Preliminary Objections — Jurisdiction", "body_html": "<p>Stub draft — Section 5.</p>"},
                {"num": 6,  "title": "Preliminary Objections — Limitation and Procedure", "body_html": "<p>Stub draft — Section 6.</p>"},
                {"num": 7,  "title": "Para-wise Reply",              "body_html": "<p>Stub draft — Section 7.</p>"},
                {"num": 8,  "title": "Ground 1",                     "body_html": "<p>Stub draft — Section 8.</p>"},
                {"num": 9,  "title": "Ground 2",                     "body_html": "<p>Stub draft — Section 9.</p>"},
                {"num": 10, "title": "Ground 3",                     "body_html": "<p>Stub draft — Section 10.</p>"},
                {"num": 11, "title": "Without Prejudice — Alternative Submissions", "body_html": "<p>Stub draft — Section 11.</p>"},
                {"num": 12, "title": "Quantum, Interest and Computation", "body_html": "<p>Stub draft — Section 12.</p>"},
                {"num": 13, "title": "Prayer",                       "body_html": "<p>Stub draft — Section 13.</p>"},
                {"num": 14, "title": "Annexures",                    "body_html": "<p>Stub draft — Section 14.</p>"},
                {"num": 15, "title": "Declaration and Signature Block", "body_html": "<p>Stub draft — Section 15.</p>"}
              ],
              "internal_partner_note": "Stub draft generated for testing.",
              "client_summary": "This is a stub response. Please configure a real LLM provider."
            }
            """;

    private static final String EXTRACTION_RESPONSE = """
            {
              "notice_info": {
                "notice_number": "SCN-STUB-001",
                "din_or_rfn": "DIN-STUB-001",
                "issue_date": "2024-01-01",
                "tax_period": "FY 2017-18",
                "authority": "Proper Officer Ward 1",
                "total_demand_amount": 100000.0
              },
              "issues": [
                {
                  "issue_id": "ISSUE-1",
                  "title": "Stub Issue 1 - ITC Mismatch",
                  "description": "Stub description for ITC mismatch discrepancy.",
                  "statutory_section": "Section 16(2)(c)"
                }
              ]
            }
            """;

    private static final String TRIAGE_RESPONSE = """
            {
              "summary": "Stub triage summary. Please configure a real LLM provider.",
              "checklist": [
                {"label": "Copy of Notice", "rationale": "Original notice required for filing", "doc_type": "notice",   "is_required": true},
                {"label": "PAN Card",       "rationale": "Identity proof",                      "doc_type": "pan_card", "is_required": true}
              ]
            }
            """;

    /** When non-null, every call loads {@code classpath:stub-canned/{cannedKey}.json}. */
    private final String cannedKey;

    /** No-arg constructor — keyword-detection mode, no canned overrides. */
    public StubLlmProvider() {
        this(null);
    }

    /**
     * @param cannedKey value of {@code noticedesk.llm.stub-default-canned}; may be null/blank.
     */
    public StubLlmProvider(String cannedKey) {
        this.cannedKey = (cannedKey != null && !cannedKey.isBlank()) ? cannedKey.trim() : null;
    }

    @Override
    public String getName() { return "stub"; }

    @Override
    public String getModel() { return "stub-model"; }

    @Override
    public LlmResponse generateText(String system, String user, int maxOutputTokens, double temperature) {
        log.debug("stub_llm_call system_length={} user_length={}", system.length(), user.length());
        String content = resolveContent(system);
        return new LlmResponse(content, "stub-model", "stub", 100, 200);
    }

    // -----------------------------------------------------------------------

    private String resolveContent(String system) {
        if (cannedKey != null) {
            String loaded = loadClasspathResource("stub-canned/" + cannedKey + ".json");
            if (loaded != null) {
                log.info("stub_llm_canned key={}", cannedKey);
                return loaded;
            }
            log.warn("stub_llm_canned_missing key={} — falling back to keyword detection", cannedKey);
        }
        return detectAgentType(system);
    }

    private String detectAgentType(String system) {
        String lower = system.toLowerCase();
        if (lower.contains("document_type") || lower.contains("parse") || lower.contains("ocr")) {
            return PARSE_RESPONSE;
        }
        if (lower.contains("issue_id") || lower.contains("extraction") || lower.contains("allegations")) {
            return EXTRACTION_RESPONSE;
        }
        if (lower.contains("sections") || lower.contains("draft") || lower.contains("reply")) {
            return DRAFT_RESPONSE;
        }
        if (lower.contains("triage") || lower.contains("checklist")) {
            return TRIAGE_RESPONSE;
        }
        return "{\"status\": \"ok\"}";
    }

    private static String loadClasspathResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (resource.exists()) {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("stub_llm_classpath_load_failed path={} error={}", path, e.getMessage());
        }
        return null;
    }
}
