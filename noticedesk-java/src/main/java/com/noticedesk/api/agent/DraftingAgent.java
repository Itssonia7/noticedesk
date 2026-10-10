package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.model.rag.ConfidenceAssessment;
import com.noticedesk.api.model.rag.RagContextBundle;
import com.noticedesk.api.service.llm.*;
import com.noticedesk.api.service.rag.RagStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Drafting Agent.
 *
 * Generates a multi-section reply draft for a notice. Loads registration-scoped
 * context, renders the v3 prompt template, calls the configured LLM, and returns
 * a validated GeneratedDraft. Falls back to secondary provider on transient errors.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftingAgent {

    private static final String PROMPT_VERSION              = "drafting_v4";
    private static final int    NOTICE_OCR_EXCERPT_CHARS    = 10_000;
    private static final int    SUPPORTING_DOC_EXCERPT_CHARS = 5_000;
    private static final int    SUPPORTING_EVIDENCE_MAX_CHARS = 10_000;
    private static final int    DRAFTING_MAX_OUTPUT_TOKENS  = 12_000;
    private static final double DRAFTING_TEMPERATURE        = 0.1;

    private final LlmFactory      llmFactory;
    private final ObjectMapper    objectMapper;
    private final RagStoreService ragStoreService;
    private final ApiUsageLogService apiUsageLogService;

    // ---- Public data types -----------------------------------------------

    public record SupportingDoc(
            String requirementLabel,
            String requirementRationale,
            String requirementDocType,
            String filename,
            String documentType,
            String excerpt) {}

    public record PendingRequirement(
            String  label,
            String  rationale,
            boolean isRequired) {}

    public record DraftSection(
            int    num,
            String title,
            String bodyHtml) {}

    public record GeneratedDraft(
            List<DraftSection> sections,
            String             internalPartnerNote,
            String             clientSummary,
            String             promptVersion,
            String             model,
            String             providerName,
            Integer            inputTokens,
            Integer            outputTokens) {}

    public record ExtractedIssue(
            String issueId,
            String title,
            String description,
            String statutorySection) {}

    public record ExtractedNoticeInfo(
            String noticeNumber,
            String dinOrRfn,
            String issueDate,
            String taxPeriod,
            String authority,
            Double totalDemandAmount) {}

    public record ExtractionResult(
            ExtractedNoticeInfo noticeInfo,
            List<ExtractedIssue> issues,
            Integer inputTokens,
            Integer outputTokens,
            String model) {
        public ExtractionResult(ExtractedNoticeInfo noticeInfo, List<ExtractedIssue> issues) {
            this(noticeInfo, issues, 0, 0, null);
        }
    }

    public record DraftingInput(
            UUID                      matterId,
            String                    tone,
            String                    partnerInstructions,
            String                    clientLegalName,
            String                    clientPan,
            String                    clientEntityType,
            String                    registrationType,
            String                    registrationIdentifier,
            String                    registrationStateName,
            String                    law,
            String                    financialYear,
            String                    assessmentYear,
            Map<String, Object>       notice,
            Map<String, Object>       rawExtractedJson,
            String                    noticeOcrExcerpt,
            List<Map<String, Object>> priorMatters,
            List<Map<String, Object>> siblingNotices,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> crossRegistrationContext,
            List<SupportingDoc>       supportingDocuments,
            List<PendingRequirement>  pendingRequirements,
            RagContextBundle          ragContext,
            ConfidenceAssessment      confidenceAssessment) {}

    // ---- Public API ------------------------------------------------------

    /**
     * Load all registration-scoped context the drafter needs.
     */
    @SuppressWarnings("unchecked")
    public DraftingInput loadDraftingInput(
            NamedParameterJdbcTemplate jdbc,
            UUID matterId,
            UUID noticeId,
            String tone,
            String partnerInstructions,
            boolean includeCrossRegistration) {

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT m.matter_id, m.registration_id, m.client_id, m.law,
                       m.financial_year, m.assessment_year,
                       c.legal_name AS client_legal_name,
                       c.pan        AS client_pan,
                       c.entity_type AS client_entity_type,
                       r.registration_type, r.identifier_value, r.state_name,
                       n.notice_id, n.document_type, n.notice_number,
                       n.din_or_rfn, n.issue_date, n.due_date, n.hearing_date,
                       n.authority, n.demand_amount, n.lifecycle_status,
                       n.raw_extracted_json,
                       n.source_inbox_id,
                       LEFT(COALESCE(ib.ocr_text, ''), :ocr_chars) AS notice_ocr_excerpt
                FROM matters m
                JOIN clients c ON c.client_id = m.client_id
                JOIN client_registrations r ON r.registration_id = m.registration_id
                JOIN notices n ON n.notice_id = :nid
                LEFT JOIN documents_inbox ib ON ib.inbox_id = n.source_inbox_id
                WHERE m.matter_id = :mid AND c.deleted_at IS NULL
                """,
                Map.of("mid", matterId.toString(), "nid", noticeId.toString(),
                        "ocr_chars", NOTICE_OCR_EXCERPT_CHARS));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("matter or notice not found: " + matterId);
        }
        Map<String, Object> row = rows.get(0);

        String registrationId = row.get("registration_id").toString();
        String clientId       = row.get("client_id").toString();

        List<Map<String, Object>> priorMatters = jdbc.queryForList(
                """
                SELECT matter_id, financial_year, assessment_year, opening_date, closing_date, status
                FROM matters
                WHERE registration_id = CAST(:rid AS UUID) AND matter_id <> CAST(:mid AS UUID)
                ORDER BY opening_date DESC NULLS LAST
                LIMIT 10
                """,
                Map.of("rid", registrationId, "mid", matterId.toString()));

        List<Map<String, Object>> siblingNotices = jdbc.queryForList(
                """
                SELECT notice_id, document_type, due_date, lifecycle_status, raw_extracted_json
                FROM notices
                WHERE registration_id = CAST(:rid AS UUID) AND notice_id <> CAST(:nid AS UUID)
                  AND lifecycle_status IN ('issued', 'in_progress', 'due', 'due_date_over')
                ORDER BY due_date ASC NULLS LAST
                LIMIT 10
                """,
                Map.of("rid", registrationId, "nid", noticeId.toString()));

        List<Map<String, Object>> docRows = jdbc.queryForList(
                """
                SELECT document_id, filename, document_type, lifecycle_stage,
                       LEFT(COALESCE(extracted_text, ''), 4000) AS excerpt
                FROM documents
                WHERE matter_id = CAST(:mid AS UUID)
                ORDER BY uploaded_at ASC
                """,
                Map.of("mid", matterId.toString()));

        List<Map<String, Object>> supportingRows = jdbc.queryForList(
                """
                SELECT r.label        AS req_label,
                       r.rationale    AS req_rationale,
                       r.doc_type     AS req_doc_type,
                       d.filename     AS doc_filename,
                       d.document_type AS doc_document_type,
                       LEFT(COALESCE(d.extracted_text, ''), :doc_chars) AS doc_excerpt
                FROM notice_document_requirements r
                JOIN documents d ON d.document_id = r.document_id
                WHERE r.notice_id = CAST(:nid AS UUID) AND r.status = 'uploaded'
                ORDER BY r.position ASC
                """,
                Map.of("nid", noticeId.toString(), "doc_chars", SUPPORTING_DOC_EXCERPT_CHARS));

        List<SupportingDoc> supportingDocs = supportingRows.stream()
                .map(r -> new SupportingDoc(
                        (String) r.get("req_label"),
                        (String) r.get("req_rationale"),
                        (String) r.get("req_doc_type"),
                        (String) r.get("doc_filename"),
                        (String) r.get("doc_document_type"),
                        r.get("doc_excerpt") != null ? (String) r.get("doc_excerpt") : ""))
                .toList();

        List<Map<String, Object>> pendingRows = jdbc.queryForList(
                """
                SELECT label, rationale, is_required
                FROM notice_document_requirements
                WHERE notice_id = CAST(:nid AS UUID) AND status = 'pending'
                ORDER BY position ASC
                """,
                Map.of("nid", noticeId.toString()));

        List<PendingRequirement> pendingReqs = pendingRows.stream()
                .map(r -> new PendingRequirement(
                        (String) r.get("label"),
                        (String) r.get("rationale"),
                        Boolean.TRUE.equals(r.get("is_required"))))
                .toList();

        List<Map<String, Object>> crossBlock = List.of();
        if (includeCrossRegistration) {
            crossBlock = jdbc.queryForList(
                    """
                    SELECT n.notice_id, n.document_type, n.due_date, n.lifecycle_status, n.law,
                           r.identifier_value, r.state_name,
                           LEFT(COALESCE(n.raw_extracted_json::TEXT, ''), 600) AS excerpt
                    FROM notices n
                    JOIN client_registrations r ON r.registration_id = n.registration_id
                    WHERE r.client_id = CAST(:cid AS UUID)
                      AND n.registration_id <> CAST(:rid AS UUID)
                      AND n.lifecycle_status IN ('issued', 'in_progress', 'due', 'due_date_over')
                    ORDER BY n.due_date ASC NULLS LAST
                    LIMIT 10
                    """,
                    Map.of("cid", clientId, "rid", registrationId));
        }

        // Build notice map
        Map<String, Object> noticeMap = new LinkedHashMap<>();
        noticeMap.put("notice_id",        row.get("notice_id") != null ? row.get("notice_id").toString() : null);
        noticeMap.put("document_type",    row.get("document_type"));
        noticeMap.put("notice_number",    row.get("notice_number"));
        noticeMap.put("din_or_rfn",       row.get("din_or_rfn"));
        noticeMap.put("issue_date",       isoDate(row.get("issue_date")));
        noticeMap.put("due_date",         isoDate(row.get("due_date")));
        noticeMap.put("hearing_date",     isoDate(row.get("hearing_date")));
        noticeMap.put("authority",        row.get("authority"));
        noticeMap.put("demand_amount",    toDouble(row.get("demand_amount")));
        noticeMap.put("lifecycle_status", row.get("lifecycle_status"));
        // Pull first-level issue from raw_extracted_json
        Object rawJsonObj = row.get("raw_extracted_json");
        if (rawJsonObj instanceof Map<?, ?> rawM) {
            noticeMap.put("issue", rawM.get("issue"));
        }

        Map<String, Object> rawExtractedJson = rawJsonObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        String queryText = noticeMap.get("issue") != null ? noticeMap.get("issue").toString() :
                (row.get("document_type") != null ? row.get("document_type").toString() : "GST Notice Allegation");

        RagContextBundle ragBundle = ragStoreService != null ? ragStoreService.getRagContext(matterId, queryText, 1, 1) : null;
        ConfidenceAssessment confidenceDec = ragStoreService != null ? ragStoreService.evaluateCascadeConfidence(ragBundle) : null;

        return new DraftingInput(
                matterId, tone,
                partnerInstructions != null ? partnerInstructions : "",
                (String) row.get("client_legal_name"),
                (String) row.get("client_pan"),
                (String) row.get("client_entity_type"),
                (String) row.get("registration_type"),
                (String) row.get("identifier_value"),
                (String) row.get("state_name"),
                (String) row.get("law"),
                (String) row.get("financial_year"),
                (String) row.get("assessment_year"),
                noticeMap,
                rawExtractedJson,
                row.get("notice_ocr_excerpt") != null ? (String) row.get("notice_ocr_excerpt") : "",
                new ArrayList<>(priorMatters),
                new ArrayList<>(siblingNotices),
                new ArrayList<>(docRows),
                new ArrayList<>(crossBlock),
                supportingDocs,
                pendingReqs,
                ragBundle,
                confidenceDec);
    }

    /**
     * Step 1: Issue Extraction via Claude Haiku.
     * Extracts structured issues and notice info. Retries once if invalid JSON is returned.
     */
    @SuppressWarnings("unchecked")
    public ExtractionResult extractIssues(DraftingInput input) {
        String system = """
                You are an expert Indian Tax Advocate assistant.
                Read the tax notice text and extract all separate issues/allegations/sub-issues, plus essential notice metadata.
                Return ONLY a valid JSON object with the following schema:
                {
                  "notice_info": {
                    "notice_number": "SCN Number or null",
                    "din_or_rfn": "DIN/RFN or null",
                    "issue_date": "YYYY-MM-DD or null",
                    "tax_period": "FY 2017-18 or null",
                    "authority": "Proper Officer Ward X or null",
                    "total_demand_amount": 0.0
                  },
                  "issues": [
                    {
                      "issue_id": "ISSUE-1",
                      "title": "Short title of the discrepancy",
                      "description": "Detailed factual and legal description of this specific issue",
                      "statutory_section": "Relevant section, e.g. Section 16(2)(c) / Section 73"
                    }
                  ]
                }
                """;

        String user = "Notice metadata:\n" + input.notice() +
                      "\n\nOCR Excerpt:\n" + input.noticeOcrExcerpt();

        LlmProvider extractor = llmFactory.getLlmForAgent("extraction");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LlmResponse resp = extractor.generateText(system, user, 4000, 0.0);
                if (apiUsageLogService != null) {
                    apiUsageLogService.logUsage(resp.providerName(), resp.model(), resp.inputTokens(), resp.outputTokens());
                }
                String raw = stripCodeFence(resp.content());
                Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<>() {});

                List<Map<String, Object>> rawIssues = (List<Map<String, Object>>) parsed.get("issues");
                if (rawIssues != null && !rawIssues.isEmpty()) {
                    List<ExtractedIssue> issues = new ArrayList<>();
                    for (int i = 0; i < rawIssues.size(); i++) {
                        Map<String, Object> m = rawIssues.get(i);
                        issues.add(new ExtractedIssue(
                                Objects.toString(m.getOrDefault("issue_id", "ISSUE-" + (i + 1)), "ISSUE-" + (i + 1)),
                                Objects.toString(m.getOrDefault("title", "Issue " + (i + 1)), "Issue " + (i + 1)),
                                Objects.toString(m.getOrDefault("description", ""), ""),
                                Objects.toString(m.getOrDefault("statutory_section", "Section 73"), "Section 73")
                        ));
                    }

                    Map<String, Object> infoMap = (Map<String, Object>) parsed.getOrDefault("notice_info", Map.of());
                    ExtractedNoticeInfo info = new ExtractedNoticeInfo(
                            Objects.toString(infoMap.get("notice_number"), null),
                            Objects.toString(infoMap.get("din_or_rfn"), null),
                            Objects.toString(infoMap.get("issue_date"), null),
                            Objects.toString(infoMap.get("tax_period"), null),
                            Objects.toString(infoMap.get("authority"), null),
                            infoMap.get("total_demand_amount") instanceof Number n ? n.doubleValue() : null
                    );

                    log.info("extract_issues_success attempt={} issuesCount={} inputTokens={} outputTokens={} model={}", attempt, issues.size(), resp.inputTokens(), resp.outputTokens(), resp.model());
                    return new ExtractionResult(info, issues, resp.inputTokens(), resp.outputTokens(), resp.model());
                }
                log.warn("extract_issues_attempt_invalid_json attempt={} raw={}", attempt, raw);
            } catch (Exception e) {
                log.warn("extract_issues_attempt_failed attempt={} error={}", attempt, e.getMessage());
            }
        }

        log.error("extract_issues_failed_after_retry");
        throw new JsonSchemaValidationException("Notice issue extraction failed: returned invalid JSON after retry");
    }

    public record OpusTemplateResult(
            String content,
            Integer inputTokens,
            Integer outputTokens,
            String model) {}

    /**
     * Step 3: Unmatched Issue Template Generation via Claude Opus.
     * Generates a statutory rebuttal template for ONLY a single unmatched issue.
     */
    public OpusTemplateResult generateOpusTemplateResultForUnmatchedIssue(ExtractedIssue issue, DraftingInput input) {
        log.info("Generating Opus template chunk for unmatched issue: {}", issue.title());
        String system = """
                You are a Senior GST Advocate. Generate a comprehensive legal rebuttal argument template for ONLY the single unmatched issue specified.
                Provide statutory grounds, CBIC circular references, and judicial precedents.
                Return clear, structured text for this issue only.
                """;

        String user = "Client: " + input.clientLegalName() + "\n" +
                      "Law: " + input.law() + "\n\n" +
                      "UNMATCHED ISSUE DETAILS:\n" +
                      "Title: " + issue.title() + "\n" +
                      "Statutory Section: " + issue.statutorySection() + "\n" +
                      "Description: " + issue.description();

        LlmProvider opusProvider = llmFactory.getOpusProvider();
        LlmResponse resp = opusProvider.generateText(system, user, 4000, 0.1);

        if (apiUsageLogService != null) {
            apiUsageLogService.logUsage(resp.providerName(), resp.model(), resp.inputTokens(), resp.outputTokens());
        }

        log.info("generate_opus_template_success issue='{}' inputTokens={} outputTokens={} model={}", issue.title(), resp.inputTokens(), resp.outputTokens(), resp.model());
        return new OpusTemplateResult(resp.content().strip(), resp.inputTokens(), resp.outputTokens(), resp.model());
    }

    public String generateOpusTemplateForUnmatchedIssue(ExtractedIssue issue, DraftingInput input) {
        return generateOpusTemplateResultForUnmatchedIssue(issue, input).content();
    }

    /**
     * Step 4: Merge & Format Draft via Claude Haiku.
     * Merges all deduplicated chunks into a complete 15-section GeneratedDraft.
     */
    public GeneratedDraft mergeAndFormatDraft(DraftingInput input, List<String> deduplicatedChunks) {
        log.info("Merging and formatting {} unique chunks into 15-section final draft via Haiku.", deduplicatedChunks.size());

        String system = """
                You are a Senior GST Advocate. Your job is to merge all provided legal chunks and notice context into a formal 15-section written reply to the GST Notice.

                EVERY SINGLE SECTION FROM 1 TO 15 MUST BE PRESENT IN THE OUTPUT JSON:
                1. Addressee and Reference Block
                2. Subject
                3. Synopsis
                4. Statement of Facts
                5. Preliminary Objections — Jurisdiction
                6. Preliminary Objections — Limitation and Procedure
                7. Para-wise Reply
                8. Ground 1
                9. Ground 2
                10. Ground 3
                11. Without Prejudice — Alternative Submissions
                12. Quantum, Interest and Computation
                13. Prayer
                14. Annexures
                15. Declaration and Signature Block

                CRITICAL CONCISENESS REQUIREMENT:
                Keep body_html concise (1-2 clear, punchy paragraphs per section, max 150 words per section) so that all 15 sections fit within token limits and the JSON is completed cleanly with stop_reason=end_turn.

                CRITICAL OUTPUT CONTRACT:
                Return ONLY a valid JSON object containing exactly 15 sections numbered 1 to 15 matching this schema. Do not output anything outside the JSON object.
                {
                  "sections": [
                    { "num": 1, "title": "Addressee and Reference Block", "body_html": "<p>...</p>" },
                    { "num": 2, "title": "Subject", "body_html": "<p>...</p>" },
                    { "num": 3, "title": "Synopsis", "body_html": "<p>...</p>" },
                    { "num": 4, "title": "Statement of Facts", "body_html": "<p>...</p>" },
                    { "num": 5, "title": "Preliminary Objections — Jurisdiction", "body_html": "<p>...</p>" },
                    { "num": 6, "title": "Preliminary Objections — Limitation and Procedure", "body_html": "<p>...</p>" },
                    { "num": 7, "title": "Para-wise Reply", "body_html": "<p>...</p>" },
                    { "num": 8, "title": "Ground 1", "body_html": "<p>...</p>" },
                    { "num": 9, "title": "Ground 2", "body_html": "<p>...</p>" },
                    { "num": 10, "title": "Ground 3", "body_html": "<p>...</p>" },
                    { "num": 11, "title": "Without Prejudice — Alternative Submissions", "body_html": "<p>...</p>" },
                    { "num": 12, "title": "Quantum, Interest and Computation", "body_html": "<p>...</p>" },
                    { "num": 13, "title": "Prayer", "body_html": "<p>...</p>" },
                    { "num": 14, "title": "Annexures", "body_html": "<p>...</p>" },
                    { "num": 15, "title": "Declaration and Signature Block", "body_html": "<p>...</p>" }
                  ],
                  "internal_partner_note": "Brief review note for partner",
                  "client_summary": "Layman summary for client"
                }
                """;

        StringBuilder chunksBlock = new StringBuilder();
        for (int i = 0; i < deduplicatedChunks.size(); i++) {
            chunksBlock.append("--- LEGAL CHUNK ").append(i + 1).append(" ---\n")
                       .append(deduplicatedChunks.get(i)).append("\n\n");
        }

        String user = renderUserPrompt(loadPromptTemplate()[1], input) +
                      "\n\n--- DEDUPLICATED RETRIEVED & GENERATED LEGAL CHUNKS ---\n" +
                      chunksBlock +
                      "\n----------------------------------------------------\n" +
                      "Generate all 15 legal sections in body_html JSON format.";

        LlmProvider formatter = llmFactory.getLlmForAgent("formatting");
        int maxTokens = DRAFTING_MAX_OUTPUT_TOKENS;
        log.info("mergeAndFormatDraft max_tokens passed={}", maxTokens);

        LlmResponse response = formatter.generateText(system, user, maxTokens, DRAFTING_TEMPERATURE);
        log.info("mergeAndFormatDraft attempt 1: max_tokens={} stop_reason={} tokens_out={}",
                maxTokens, response.stopReason(), response.outputTokens());

        Map<String, Object> payload = parsePayload(response.content());
        Set<Integer> missingSections = validate15SectionsContract(payload);

        if (!missingSections.isEmpty()) {
            log.warn("mergeAndFormatDraft attempt 1 contract validation failed. Missing section numbers: {}. Retrying once with explicit instruction.", missingSections);

            String retryUser = user + "\n\nCRITICAL RETRY CONTRACT REQUIREMENT:\n" +
                    "Your previous JSON response was missing section numbers " + missingSections + ".\n" +
                    "You MUST output a valid JSON containing ALL 15 sections numbered contiguous 1 through 15 with no missing section numbers. Re-generate the complete 15-section JSON object now.";

            LlmResponse retryResponse = formatter.generateText(system, retryUser, maxTokens, DRAFTING_TEMPERATURE);
            log.info("mergeAndFormatDraft attempt 2: max_tokens={} stop_reason={} tokens_out={}",
                    maxTokens, retryResponse.stopReason(), retryResponse.outputTokens());

            payload = parsePayload(retryResponse.content());
            Set<Integer> retryMissing = validate15SectionsContract(payload);
            if (!retryMissing.isEmpty()) {
                throw new JsonSchemaValidationException("mergeAndFormatDraft failed 15-section contract validation after 1 retry. Missing sections: " + retryMissing);
            }
            response = retryResponse;
        }

        return buildGeneratedDraftFromPayload(payload, formatter, response);
    }

    /**
     * Generate the draft via primary LLM with secondary fallback.
     */
    public GeneratedDraft generateDraft(DraftingInput input) {
        return generateDraft(input, null);
    }

    /**
     * Case 4: Generate draft using Anthropic Claude Opus for novel/unknown notices.
     */
    public GeneratedDraft generateOpusDraft(DraftingInput input) {
        log.info("Case 4 triggered: Routing novel notice to Claude Opus for deep legal drafting.");
        String system = """
                You are a Senior GST Advocate in India with 15+ years of litigation experience.
                Your job is to draft a comprehensive, formal, and legally robust written reply to the GST Notice.

                CRITICAL OUTPUT CONTRACT:
                Return ONLY a valid JSON object matching this schema. Do not output anything outside the JSON object.
                {
                  "sections": [
                    { "num": 1, "title": "Section Title", "body_html": "<p>Legal argument text...</p>" }
                  ],
                  "internal_partner_note": "Brief review note for partner",
                  "client_summary": "Layman summary for client"
                }

                Draft 5-7 core legal sections (Header/Addressee, Statement of Facts, Statutory Rebuttal Grounds A-C under Sec 73/74/54, CBIC Circular compliance, and Prayer for Relief). Keep body_html concise (1-2 paragraphs per section) so the JSON is completely formed.
                """;

        String[] prompts = loadPromptTemplate();
        String userTemplate = prompts[1];
        String user = renderUserPrompt(userTemplate, input) +
                "\n\n[NOVEL NOTICE SPECIFICATION]: Generate a formal 5-7 section legal reply. Output ONLY valid JSON.";

        LlmProvider opusProvider = llmFactory.getOpusProvider();
        try {
            return callProvider(opusProvider, system, user);
        } catch (Exception e) {
            log.warn("Opus provider call failed: {}, attempting fallback parsing or retry", e.getMessage());
            return generateDraft(input, null);
        }
    }

    /**
     * Generate draft with corpus reference draft legal context.
     */
    public GeneratedDraft generateDraft(DraftingInput input, String corpusReferenceDraft) {
        String[] prompts = loadPromptTemplate();
        String system       = prompts[0];
        String userTemplate = prompts[1];
        String user         = renderUserPrompt(userTemplate, input) +
                "\n\nCRITICAL OUTPUT REQUIREMENT:\n" +
                "Draft 5-7 core legal sections (Header/Addressee, Statement of Facts, Statutory Rebuttal Grounds, CBIC Circular compliance, and Prayer for Relief). Keep body_html concise (1-2 clear paragraphs per section) so that the JSON output is completely formed and valid.";

        if (corpusReferenceDraft != null && !corpusReferenceDraft.isBlank()) {
            user += "\n\n--- GST CORPUS GOLD-STANDARD REFERENCE DRAFT ---\n" +
                    "Adopt the statutory grounds, section references, case precedents, and legal argument layout from this reference draft:\n\n" +
                    corpusReferenceDraft + "\n----------------------------------------------------\n";
        }

        LlmProvider primary = llmFactory.getLlmForAgent("drafting");
        try {
            return callProvider(primary, system, user);
        } catch (LlmTransientException e) {
            log.warn("drafting_primary_transient provider={} error={}", primary.getName(), e.getMessage());
        } catch (JsonSchemaValidationException e) {
            log.warn("drafting_primary_invalid_json provider={} error={}", primary.getName(), e.getMessage());
        }

        Optional<LlmProvider> secondary = llmFactory.getSecondaryLlmForAgent("drafting");
        if (secondary.isEmpty()) {
            throw new LlmException("drafting primary failed and no secondary configured");
        }
        return callProvider(secondary.get(), system, user);
    }

    // ---- Private: LLM call -----------------------------------------------

    @SuppressWarnings("unchecked")
    private GeneratedDraft callProvider(LlmProvider provider, String system, String user) {
        LlmResponse response = provider.generateText(
                system, user, DRAFTING_MAX_OUTPUT_TOKENS, DRAFTING_TEMPERATURE);
        Map<String, Object> payload = parsePayload(response.content());
        return buildGeneratedDraftFromPayload(payload, provider, response);
    }

    private Map<String, Object> tryRepairOrExtractSections(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String trimmed = raw.trim();
        List<String> attempts = List.of(
                trimmed,
                trimmed + "\"",
                trimmed + "\"}]}",
                trimmed + "\"}\n]}",
                trimmed + "\"}\n]}\n}"
        );

        for (String attempt : attempts) {
            try {
                Map<String, Object> p = objectMapper.readValue(attempt, new TypeReference<>() {});
                if (p.get("sections") instanceof List<?> l && !l.isEmpty()) {
                    log.info("Successfully repaired truncated JSON output from LLM!");
                    return p;
                }
            } catch (Exception ignored) {}
        }

        // Backward scan for closing brace '}' to slice completed sections
        int idx = trimmed.lastIndexOf('}');
        while (idx > 20) {
            String sliced = trimmed.substring(0, idx + 1).trim();
            if (sliced.endsWith(",")) {
                sliced = sliced.substring(0, sliced.length() - 1).trim();
            }
            List<String> fixes = List.of(
                    sliced + "\n]}",
                    sliced + "\n]}\n}",
                    sliced + "\n}"
            );
            for (String fix : fixes) {
                try {
                    Map<String, Object> p = objectMapper.readValue(fix, new TypeReference<>() {});
                    if (p.get("sections") instanceof List<?> l && !l.isEmpty()) {
                        log.info("Successfully sliced at closing brace (idx={}) & repaired truncated JSON sections from LLM!", idx);
                        return p;
                    }
                } catch (Exception ignored) {}
            }
            idx = trimmed.lastIndexOf('}', idx - 1);
        }

        List<Map<String, Object>> sections = new ArrayList<>();

        // Match individual completed JSON section objects: {"num": 1, "title": "...", "body_html": "..."}
        java.util.regex.Pattern secObjPattern = java.util.regex.Pattern.compile(
                "\\{\\s*\"num\"\\s*:[^\\}]*?\"title\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"body_html\"\\s*:\\s*\".*?\"\\s*\\}",
                java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher matcher = secObjPattern.matcher(raw);
        while (matcher.find()) {
            try {
                Map<String, Object> sec = objectMapper.readValue(matcher.group(0), new TypeReference<>() {});
                if (sec.containsKey("num") && sec.containsKey("title") && sec.containsKey("body_html")) {
                    sections.add(sec);
                }
            } catch (Exception ignored) {}
        }

        if (sections.isEmpty()) {
            String[] lines = raw.split("\n");
            int currentNum = 0;
            String currentTitle = null;
            StringBuilder currentBody = new StringBuilder();

            for (String line : lines) {
                String l = line.strip();
                if (l.matches("(?i)^(SECTION|GROUND|PART|\\d+[\\.\\)])\\s+.*") || l.startsWith("##") || l.startsWith("#")) {
                    if (currentTitle != null && currentBody.length() > 0) {
                        Map<String, Object> sec = new LinkedHashMap<>();
                        sec.put("num", ++currentNum);
                        sec.put("title", currentTitle);
                        sec.put("body_html", currentBody.toString().strip());
                        sections.add(sec);
                        currentBody.setLength(0);
                    }
                    currentTitle = l.replaceAll("^#+\\s*", "").replaceAll("(?i)^(SECTION|GROUND)\\s*\\d*[:\\-]*\\s*", "").strip();
                    if (currentTitle.isEmpty()) currentTitle = "Legal Ground " + (currentNum + 1);
                } else if (currentTitle != null && !l.isEmpty()) {
                    currentBody.append("<p>").append(l).append("</p>\n");
                }
            }
            if (currentTitle != null && currentBody.length() > 0) {
                Map<String, Object> sec = new LinkedHashMap<>();
                sec.put("num", ++currentNum);
                sec.put("title", currentTitle);
                sec.put("body_html", currentBody.toString().strip());
                sections.add(sec);
            }
        }

        if (!sections.isEmpty()) {
            log.info("Extracted {} sections from LLM response payload!", sections.size());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sections", sections);
            payload.put("internal_partner_note", "Parsed from LLM response payload.");
            payload.put("client_summary", "Summary of legal grounds.");
            return payload;
        }

        return null;
    }

    // ---- Private: prompt loading -----------------------------------------

    private String[] loadPromptTemplate() {
        String path = "prompts/" + PROMPT_VERSION + ".md";
        try {
            String text = new ClassPathResource(path)
                    .getContentAsString(StandardCharsets.UTF_8);
            int split = text.indexOf("## User prompt template");
            if (split < 0) {
                throw new LlmException(PROMPT_VERSION + ".md is missing '## User prompt template'");
            }
            return new String[]{
                    extractFenced(text.substring(0, split)),
                    extractFenced(text.substring(split))
            };
        } catch (IOException e) {
            throw new LlmException("prompt not found at classpath:" + path);
        }
    }

    private String extractFenced(String s) {
        int start = s.indexOf("```");
        if (start < 0) return "";
        int nl  = s.indexOf('\n', start);
        if (nl  < 0) return "";
        int end = s.indexOf("```", nl + 1);
        if (end < 0) return "";
        return s.substring(nl + 1, end).strip();
    }

    // ---- Private: user-prompt rendering ----------------------------------

    private String renderUserPrompt(String template, DraftingInput di) {
        String stateQualifier = di.registrationStateName() != null
                ? " (" + di.registrationStateName() + ")" : "";
        String fyOrAy = di.financialYear() != null
                ? "FY " + di.financialYear()
                : (di.assessmentYear() != null ? "AY " + di.assessmentYear() : "—");

        String priorBlock = formatList(di.priorMatters(), m -> {
            String period = firstNonNull(str(m.get("financial_year")), str(m.get("assessment_year")), "—");
            String status = firstNonNull(str(m.get("status")), "open");
            return "- matter " + m.get("matter_id") + " · " + period + " · status " + status;
        }, "(none — first matter on this registration)");

        String siblingBlock = formatList(di.siblingNotices(), n ->
                "- " + n.get("document_type") + " due " + n.get("due_date") +
                " · " + n.get("lifecycle_status"),
                "(no other open notices on this registration)");

        String docsBlock = formatList(di.documents(), d -> {
            String kind  = firstNonNull(str(d.get("document_type")), "unspecified");
            String stage = firstNonNull(str(d.get("lifecycle_stage")), "received");
            String excpt = truncate(firstNonNull(str(d.get("excerpt")), ""), 280);
            return "- [" + kind + "] " + d.get("filename") + " (stage: " + stage + ")\n  excerpt: " + excpt;
        }, "(no documents attached yet)");

        String crossBlock = formatList(di.crossRegistrationContext(), n ->
                "- " + firstNonNull(str(n.get("state_name")), "other registration") +
                " · " + n.get("document_type") + " · due " + n.get("due_date"),
                "(none — partner did not attach cross-registration context)");

        String ocrBlock = (di.noticeOcrExcerpt() != null && !di.noticeOcrExcerpt().isBlank())
                ? di.noticeOcrExcerpt().strip()
                : "(notice was manually entered; no OCR text available — rely on " +
                  "the parsed fields + structured JSON above for the para-wise reply)";

        String supportingEvidenceBlock = renderSupportingEvidence(
                di.supportingDocuments(), di.pendingRequirements(), di.ragContext(), di.confidenceAssessment());

        String demandStr = di.notice().get("demand_amount") instanceof Number n
                ? "₹" + String.format("%.2f", n.doubleValue()) : "—";

        String rawJsonStr;
        try {
            rawJsonStr = objectMapper.writeValueAsString(di.rawExtractedJson());
        } catch (Exception e) {
            rawJsonStr = "{}";
        }

        String legalLibraryBlock = (di.ragContext() != null && !di.ragContext().legalChunks().isEmpty())
                ? di.ragContext().legalChunks().stream()
                .map(c -> String.format("<chunk id=\"%s\" source=\"%s\" citation=\"%s\">\n%s\n</chunk>",
                        c.chunkId(), c.actOrCircular() != null ? c.actOrCircular() : "legal_library",
                        c.sectionOrPara() != null ? c.sectionOrPara() : "Statute / Precedent", c.content()))
                .reduce((a, b) -> a + "\n" + b).orElse("(no legal library chunks attached)")
                : "(no legal library chunks attached)";

        String allegationsBlock = di.notice().get("issue") != null
                ? "<allegation num=\"1\">" + di.notice().get("issue") + "</allegation>"
                : "<allegation num=\"1\">General Allegation under Tax Notice</allegation>";

        String sectionInvoked = di.notice().get("section") != null ? di.notice().get("section").toString()
                : (di.law() != null ? di.law() : "Section 73/74 CGST Act, 2017");

        String tradeName = di.clientLegalName();
        String jurisdictionOffice = di.registrationStateName() != null ? di.registrationStateName() + " Tax Circle/Ward" : "Jurisdictional Office";

        return template
                .replace("{client.legal_name}",            di.clientLegalName())
                .replace("{client.trade_name}",            tradeName)
                .replace("{client.pan}",                   di.clientPan())
                .replace("{client.entity_type}",           nvl(di.clientEntityType()))
                .replace("{registration_type}",            di.registrationType())
                .replace("{registration.identifier_value}", di.registrationIdentifier())
                .replace("{registration.state_name}",      nvl(di.registrationStateName()))
                .replace("{registration.jurisdiction_office}", jurisdictionOffice)
                .replace("{state_qualifier}",              stateQualifier)
                .replace("{law}",                          di.law())
                .replace("{fy_or_ay}",                     fyOrAy)
                .replace("{notice.tax_period}",            fyOrAy)
                .replace("{notice.document_type}",         nvl(str(di.notice().get("document_type"))))
                .replace("{notice.section}",               sectionInvoked)
                .replace("{notice.din_or_rfn}",            nvl(str(di.notice().get("din_or_rfn"))))
                .replace("{notice.notice_number}",         nvl(str(di.notice().get("notice_number"))))
                .replace("{notice.issue_date}",            nvl(str(di.notice().get("issue_date"))))
                .replace("{notice.due_date}",              nvl(str(di.notice().get("due_date"))))
                .replace("{notice.authority}",             nvl(str(di.notice().get("authority"))))
                .replace("{notice.issue}",                 nvl(str(di.notice().get("issue"))))
                .replace("{notice.demand_amount}",         demandStr)
                .replace("{notice.allegations_block}",     allegationsBlock)
                .replace("{notice_ocr_excerpt}",           ocrBlock)
                .replace("{supporting_evidence_block}",    supportingEvidenceBlock)
                .replace("{legal_library_block}",          legalLibraryBlock)
                .replace("{raw_extracted_json}",           rawJsonStr)
                .replace("{prior_matters_block}",          priorBlock)
                .replace("{sibling_notices_block}",        siblingBlock)
                .replace("{documents_block}",              docsBlock)
                .replace("{cross_registration_block}",     crossBlock)
                .replace("{tone}",                         di.tone())
                .replace("{length}",                       "full")
                .replace("{partner_instructions}",
                        (di.partnerInstructions() != null && !di.partnerInstructions().isBlank())
                                ? di.partnerInstructions().strip() : "(none)");
    }

    private String renderSupportingEvidence(
            List<SupportingDoc> docs, List<PendingRequirement> pending,
            RagContextBundle ragContext, ConfidenceAssessment confidenceAssessment) {

        StringBuilder sb = new StringBuilder();

        if (confidenceAssessment != null && confidenceAssessment.isHighConfidence() && confidenceAssessment.guidedTemplate() != null) {
            sb.append(confidenceAssessment.guidedTemplate()).append("\n\n");
        }

        if (ragContext != null && (!ragContext.legalChunks().isEmpty() || !ragContext.evidenceChunks().isEmpty())) {
            sb.append(ragContext.formatForPrompt()).append("\n\n");
        }

        if (docs.isEmpty() && pending.isEmpty() && sb.length() == 0) {
            return "(no triage checklist for this notice — either triage hasn't been run yet " +
                   "or the matter has no document requirements. " +
                   "Fall back to the DOCUMENTS ATTACHED block below.)";
        }

        if (!docs.isEmpty()) {
            sb.append("ATTACHED (use these as primary evidence):");
            int budget = SUPPORTING_EVIDENCE_MAX_CHARS;
            for (int i = 0; i < docs.size(); i++) {
                SupportingDoc d = docs.get(i);
                sb.append("\n[").append(i + 1).append("] ").append(d.requirementLabel())
                  .append("\n    rationale: ").append(d.requirementRationale())
                  .append("\n    document : ").append(d.filename());
                if (d.documentType() != null) sb.append(" (").append(d.documentType()).append(")");
                sb.append("\n    excerpt  :\n");
                String excerpt = (d.excerpt() != null ? d.excerpt() : "").strip();
                if (excerpt.isEmpty()) {
                    sb.append("        (no extracted text available for this document)");
                    continue;
                }
                if (budget <= 0) {
                    sb.append("        (excerpt omitted — total supporting-evidence budget " +
                              "exceeded; reference by filename only)");
                    continue;
                }
                String chunk = excerpt.substring(0, Math.min(excerpt.length(), budget));
                sb.append("        ").append(chunk.replace("\n", "\n        "));
                budget -= chunk.length();
            }
        }

        if (!pending.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("\nPENDING — not attached (write [DOCUMENT REQUESTED] for facts " +
                      "that would have come from these):");
            for (PendingRequirement p : pending) {
                sb.append("\n  - [").append(p.isRequired() ? "REQUIRED" : "optional").append("] ")
                  .append(p.label()).append(" — ").append(p.rationale());
            }
        }

        return sb.toString();
    }

    public Set<Integer> validate15SectionsContract(Map<String, Object> payload) {
        if (payload == null || !(payload.get("sections") instanceof List<?> sections)) {
            Set<Integer> allMissing = new TreeSet<>();
            for (int i = 1; i <= 15; i++) allMissing.add(i);
            return allMissing;
        }

        Set<Integer> presentNumbers = new HashSet<>();
        for (Object s : sections) {
            if (s instanceof Map<?, ?> m) {
                Object numObj = m.get("num");
                if (numObj instanceof Number n) {
                    presentNumbers.add(n.intValue());
                } else if (numObj instanceof String str && str.matches("\\d+")) {
                    presentNumbers.add(Integer.parseInt(str));
                }
            }
        }

        Set<Integer> missing = new TreeSet<>();
        for (int i = 1; i <= 15; i++) {
            if (!presentNumbers.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    private Map<String, Object> parsePayload(String rawContent) {
        String raw = stripCodeFence(rawContent);
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            payload = tryRepairOrExtractSections(raw);
            if (payload == null) {
                throw new JsonSchemaValidationException("drafter returned non-JSON: " + e.getMessage());
            }
        }
        validatePayload(payload);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private GeneratedDraft buildGeneratedDraftFromPayload(Map<String, Object> payload, LlmProvider provider, LlmResponse response) {
        List<Map<String, Object>> rawSections =
                (List<Map<String, Object>>) payload.get("sections");
        List<DraftSection> sections = rawSections.stream()
                .map(s -> {
                    Object numObj = s.get("num");
                    int num = (numObj instanceof Number n) ? n.intValue() : Integer.parseInt(numObj.toString());
                    return new DraftSection(
                            num,
                            (String) s.get("title"),
                            (String) s.get("body_html"));
                })
                .toList();

        log.info("draft generated provider={} model={} sections={} tokens_out={} stop_reason={}",
                provider.getName(), response.model(), sections.size(), response.outputTokens(), response.stopReason());

        if (apiUsageLogService != null) {
            apiUsageLogService.logUsage(response.providerName(), response.model(), response.inputTokens(), response.outputTokens());
        }

        return new GeneratedDraft(
                sections,
                Objects.toString(payload.getOrDefault("internal_partner_note", ""), ""),
                Objects.toString(payload.getOrDefault("client_summary", ""), ""),
                PROMPT_VERSION,
                response.model(),
                response.providerName(),
                response.inputTokens(),
                response.outputTokens());
    }

    // ---- Private: validation ---------------------------------------------

    @SuppressWarnings("unchecked")
    private void validatePayload(Map<String, Object> payload) {
        if (!(payload.get("sections") instanceof List<?> sections) || sections.isEmpty()) {
            throw new JsonSchemaValidationException("drafting response must include sections[]");
        }
        Set<Integer> seen = new HashSet<>();
        for (Object s : sections) {
            if (!(s instanceof Map<?, ?> m)) {
                throw new JsonSchemaValidationException("each section must be an object");
            }
            Object numObj = m.get("num");
            int num;
            if (numObj instanceof Number n) {
                num = n.intValue();
            } else if (numObj instanceof String str && str.matches("\\d+")) {
                num = Integer.parseInt(str);
            } else {
                throw new JsonSchemaValidationException("each section.num must be an int");
            }
            if (!seen.add(num)) {
                throw new JsonSchemaValidationException("duplicate section.num " + num);
            }
            if (!(m.get("title") instanceof String t) || t.isBlank()) {
                throw new JsonSchemaValidationException("each section.title must be a non-empty string");
            }
            if (!(m.get("body_html") instanceof String)) {
                throw new JsonSchemaValidationException("each section.body_html must be a string");
            }
        }
    }

    // ---- Utilities -------------------------------------------------------

    private <T> String formatList(List<T> items, Function<T, String> fmt, String empty) {
        if (items.isEmpty()) return empty;
        StringBuilder sb = new StringBuilder();
        for (T item : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(fmt.apply(item));
        }
        return sb.toString();
    }

    private String stripCodeFence(String text) {
        String s = text.strip();
        if (!s.startsWith("```")) return s;
        String[] lines = s.split("\n", -1);
        int start = lines[0].startsWith("```") ? 1 : 0;
        int end   = lines[lines.length - 1].strip().startsWith("```")
                ? lines.length - 1 : lines.length;
        return String.join("\n", Arrays.copyOfRange(lines, start, end)).strip();
    }

    private String nvl(String s) { return s != null ? s : "—"; }
    private String str(Object o) { return o instanceof String s ? s : null; }

    private String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String isoDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate().toString();
        return o.toString();
    }

    private Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }
}
