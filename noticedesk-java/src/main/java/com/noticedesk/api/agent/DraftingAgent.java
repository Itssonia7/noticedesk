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
    private static final int    NOTICE_OCR_EXCERPT_CHARS    = 20_000;
    private static final int    SUPPORTING_DOC_EXCERPT_CHARS = 5_000;
    private static final int    SUPPORTING_EVIDENCE_MAX_CHARS = 40_000;
    private static final int    DRAFTING_MAX_OUTPUT_TOKENS  = 16_000;
    private static final double DRAFTING_TEMPERATURE        = 0.1;

    private final LlmFactory      llmFactory;
    private final ObjectMapper    objectMapper;
    private final RagStoreService ragStoreService;

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

        RagContextBundle ragBundle = ragStoreService != null ? ragStoreService.getRagContext(matterId, queryText, 5, 5) : null;
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
     * Generate the draft via primary LLM with secondary fallback.
     */
    public GeneratedDraft generateDraft(DraftingInput input) {
        return generateDraft(input, null);
    }

    /**
     * Generate draft with corpus reference draft legal context.
     */
    public GeneratedDraft generateDraft(DraftingInput input, String corpusReferenceDraft) {
        String[] prompts = loadPromptTemplate();
        String system       = prompts[0];
        String userTemplate = prompts[1];
        String user         = renderUserPrompt(userTemplate, input);

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

        String raw = stripCodeFence(response.content());
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            throw new JsonSchemaValidationException("drafter returned non-JSON: " + e.getMessage());
        }

        validatePayload(payload);

        List<Map<String, Object>> rawSections =
                (List<Map<String, Object>>) payload.get("sections");
        List<DraftSection> sections = rawSections.stream()
                .map(s -> new DraftSection(
                        ((Number) s.get("num")).intValue(),
                        (String) s.get("title"),
                        (String) s.get("body_html")))
                .toList();

        log.info("draft generated provider={} model={} sections={} tokens_out={}",
                provider.getName(), response.model(), sections.size(), response.outputTokens());

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
            if (!(m.get("num") instanceof Number)) {
                throw new JsonSchemaValidationException("each section.num must be an int");
            }
            int num = ((Number) m.get("num")).intValue();
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
