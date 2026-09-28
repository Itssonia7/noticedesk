package com.noticedesk.api.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.CitationVerificationResult;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.GstCorpusMatcherService;
import com.noticedesk.api.service.GstTemplateFillerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Drafting workflow — end-to-end draft generation.
 *
 * Loads registration-scoped context, generates the draft, verifies citations,
 * persists the draft + citation rows, and emits the audit event — all in a
 * single database transaction.
 */
import com.noticedesk.api.service.rag.RagStoreService;

@Service
@RequiredArgsConstructor
@Slf4j
public class DraftingWorkflow {

    private final NamedParameterJdbcTemplate  jdbc;
    private final DraftingAgent               draftingAgent;
    private final CitationVerificationAgent   citationVerificationAgent;
    private final AuditService                auditService;
    private final AppProperties               properties;
    private final ObjectMapper                objectMapper;
    private final GstCorpusMatcherService     corpusMatcherService;
    private final GstTemplateFillerService     templateFillerService;
    private final RagStoreService             ragStoreService;

    // ---- Public data types -----------------------------------------------

    public record GenerateDraftJob(
            UUID    tenantId,
            UUID    userId,
            UUID    matterId,
            UUID    noticeId,
            String  tone,
            String  partnerInstructions,
            boolean includeCrossRegistration) {}

    public record GenerateDraftResult(
            UUID                draftId,
            int                 version,
            Map<String, Object> citationSummary,
            int                 sectionsKept) {}

    // ---- Public API ------------------------------------------------------

    @Transactional
    public GenerateDraftResult runGenerateDraft(GenerateDraftJob job) {
        log.info("draft.start matter_id={} notice_id={} tenant_id={}",
                job.matterId(), job.noticeId(), job.tenantId());

        // 1. Bind RLS
        jdbc.queryForObject("SELECT set_config('app.current_tenant', :tid, true)",
                Map.of("tid", job.tenantId().toString()), String.class);

        // 2. Load drafting context
        DraftingInput input = draftingAgent.loadDraftingInput(
                jdbc,
                job.matterId(),
                job.noticeId(),
                job.tone(),
                job.partnerInstructions(),
                job.includeCrossRegistration());

        // 3. Required 5-Step Pipeline (Option A)
        // Step 1: Issue Extraction via Claude Haiku
        DraftingAgent.ExtractionResult extractionResult = draftingAgent.extractIssues(input);
        log.info("Step 1 Issue Extraction complete: extracted {} issues", extractionResult.issues().size());

        // Step 2: Per-Issue RAG Search & Chunk Deduplication
        double similarityThreshold = properties.getDrafting().getSimilarityThreshold();
        Set<String> uniqueChunkContents = new LinkedHashSet<>();
        List<DraftingAgent.ExtractedIssue> unmatchedIssues = new ArrayList<>();

        for (DraftingAgent.ExtractedIssue issue : extractionResult.issues()) {
            String query = issue.title() + " " + issue.description();
            List<com.noticedesk.api.model.rag.LegalChunk> searchResults = ragStoreService.searchLegal(query, 1);

            if (!searchResults.isEmpty() && searchResults.get(0).similarityScore() != null
                    && searchResults.get(0).similarityScore() >= similarityThreshold) {
                com.noticedesk.api.model.rag.LegalChunk match = searchResults.get(0);
                log.info("Issue '{}' MATCHED RAG chunk id={} score={}", issue.title(), match.chunkId(), match.similarityScore());
                uniqueChunkContents.add(match.content()); // Deduplicated automatically!
            } else {
                log.info("Issue '{}' UNMATCHED in RAG (score below threshold {})", issue.title(), similarityThreshold);
                unmatchedIssues.add(issue);
            }
        }

        // Step 3: Unmatched Issues - Claude Opus Template Generation & RAG Caching
        for (DraftingAgent.ExtractedIssue unmatched : unmatchedIssues) {
            String opusTemplate = draftingAgent.generateOpusTemplateForUnmatchedIssue(unmatched, input);
            ragStoreService.saveNewChunk(unmatched.title(), opusTemplate);
            uniqueChunkContents.add(opusTemplate);
        }

        if (uniqueChunkContents.isEmpty()) {
            uniqueChunkContents.add("General Statutory Ground under Tax Law Notice Response");
        }

        // Step 4: Merge & Format into 15-Section Draft via Claude Haiku
        GeneratedDraft generated = draftingAgent.mergeAndFormatDraft(input, new ArrayList<>(uniqueChunkContents));

        // Step 5: Verify citations — concatenate all section body_html for extraction
        String allHtml = generated.sections().stream()
                .map(DraftSection::bodyHtml)
                .reduce("", (a, b) -> a + "\n" + b);
        String citationProvider = resolveCitationProvider();
        CitationVerificationResult citationResult =
                citationVerificationAgent.verify(allHtml, citationProvider);

        // 5. Compute next draft version for this matter
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM drafts WHERE matter_id = CAST(:mid AS UUID)",
                Map.of("mid", job.matterId().toString()),
                Integer.class);

        // 6. Convert sections to JSON-serialisable list
        List<Map<String, Object>> sectionsData = generated.sections().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("num",       s.num());
                    m.put("title",     s.title());
                    m.put("body_html", s.bodyHtml());
                    return m;
                })
                .toList();

        // 7. Paragraph → source map (stub: empty for now)
        List<Map<String, Object>> paragraphToSourceMap = List.of();

        String sectionsJson;
        String citationSummaryJson;
        String pmapJson;
        try {
            sectionsJson       = objectMapper.writeValueAsString(sectionsData);
            citationSummaryJson = objectMapper.writeValueAsString(citationResult.summary());
            pmapJson           = objectMapper.writeValueAsString(paragraphToSourceMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise draft data: " + e.getMessage(), e);
        }

        // 8. INSERT draft row
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO drafts (
                    draft_id, tenant_id, matter_id, version, status,
                    model_used, prompt_version, tone,
                    sections, citation_summary, paragraph_to_source_map,
                    internal_partner_note, content, generated_at, generated_by_user_id
                ) VALUES (
                    CAST(:did AS UUID), CAST(:tid AS UUID), CAST(:mid AS UUID),
                    :ver, 'draft',
                    :model, :pv, :tone,
                    CAST(:sections AS JSONB),
                    CAST(:csum AS JSONB),
                    CAST(:pmap AS JSONB),
                    :ipn,
                    CAST(:sections AS JSONB),
                    NOW(),
                    CAST(:uid AS UUID)
                )
                """,
                buildDraftParams(draftId, job, nextVersion, generated,
                        sectionsJson, citationSummaryJson, pmapJson));

        // 9. INSERT one row per verified citation (including stripped)
        for (VerifiedCitation vc : citationResult.citations()) {
            Map<String, Object> cp = new HashMap<>();
            cp.put("tid",    job.tenantId().toString());
            cp.put("did",    draftId.toString());
            cp.put("case",   vc.caseName());
            cp.put("cs",     vc.citationString());
            cp.put("prop",   vc.rawText());
            cp.put("status", vc.status());
            cp.put("url",    vc.sourceUrl());
            cp.put("ptext",  vc.verifiedParagraphText());
            cp.put("conf",   vc.propositionMatchConfidence());
            cp.put("act",    vc.actionTaken());
            jdbc.update(
                    """
                    INSERT INTO citations (
                        tenant_id, draft_id, case_name, citation_string,
                        proposition_for_which_cited, status, source_url,
                        verification_tier, verified_paragraph_text,
                        proposition_match_confidence, verified_at, action_taken
                    ) VALUES (
                        CAST(:tid AS UUID), CAST(:did AS UUID), :case, :cs,
                        :prop, :status, :url,
                        1, :ptext, :conf, NOW(), :act
                    )
                    """,
                    cp);
        }

        // 10. Advance notice to in_progress if still in issued state
        jdbc.update(
                "UPDATE notices SET lifecycle_status = 'in_progress' " +
                "WHERE notice_id = CAST(:nid AS UUID) AND lifecycle_status = 'issued'",
                Map.of("nid", job.noticeId().toString()));

        // 11. Emit audit log
        auditService.emit(
                job.tenantId().toString(),
                job.userId().toString(),
                "draft.generated",
                "drafts",
                draftId.toString(),
                null,
                Map.of(
                        "matter_id",        job.matterId().toString(),
                        "notice_id",        job.noticeId().toString(),
                        "version",          nextVersion,
                        "model",            generated.model(),
                        "prompt_version",   generated.promptVersion(),
                        "tone",             job.tone(),
                        "sections",         sectionsData.size(),
                        "citation_summary", citationResult.summary()),
                1);

        log.info("draft.complete draft_id={} version={} sections={} citations={}",
                draftId, nextVersion, sectionsData.size(), citationResult.citations().size());

        return new GenerateDraftResult(
                draftId,
                nextVersion,
                citationResult.summary(),
                sectionsData.size());
    }

    // ---- Private helpers -------------------------------------------------

    private Map<String, Object> buildDraftParams(
            UUID draftId, GenerateDraftJob job, Integer version,
            GeneratedDraft generated, String sectionsJson,
            String citationSummaryJson, String pmapJson) {
        Map<String, Object> p = new HashMap<>();
        p.put("did",      draftId.toString());
        p.put("tid",      job.tenantId().toString());
        p.put("mid",      job.matterId().toString());
        p.put("ver",      version);
        p.put("model",    generated.model());
        p.put("pv",       generated.promptVersion());
        p.put("tone",     job.tone());
        p.put("sections", sectionsJson);
        p.put("csum",     citationSummaryJson);
        p.put("pmap",     pmapJson);
        p.put("ipn",      generated.internalPartnerNote());
        p.put("uid",      job.userId().toString());
        return p;
    }

    private void cacheNovelDraft(DraftingInput input, GeneratedDraft generated) {
        try {
            String issue = input.notice().get("issue") != null ? input.notice().get("issue").toString() : "Novel Notice Allegation";
            String title = "Reply for " + input.clientLegalName() + " - " + issue;
            String fullContent = generated.sections().stream()
                    .map(s -> s.title() + ": " + s.bodyHtml())
                    .reduce("", (a, b) -> a + "\n" + b);
            ragStoreService.cacheNovelDraft(issue, title, fullContent);
        } catch (Exception e) {
            log.warn("Failed to auto-cache novel draft into RAG: {}", e.getMessage());
        }
    }

    private void savePartialMatchNewChunk(DraftingInput input, GeneratedDraft generated) {
        try {
            String issue = input.notice().get("issue") != null ? input.notice().get("issue").toString() : "Partial Match New Ground";
            String newChunkContent = generated.sections().stream()
                    .map(s -> s.title() + ": " + s.bodyHtml())
                    .reduce("", (a, b) -> a + "\n" + b);
            ragStoreService.saveNewChunk(issue, newChunkContent);
        } catch (Exception e) {
            log.warn("Failed to save partial match new chunk into RAG: {}", e.getMessage());
        }
    }

    /**
     * Resolve the citation provider name, defaulting to "stub" if not configured.
     * Reads from {@code CITATION_PROVIDER} env var as a secondary fallback.
     */
    private String resolveCitationProvider() {
        String env = System.getenv("CITATION_PROVIDER");
        if (env != null && !env.isBlank()) return env;
        String configured = properties.getDocumentParsing().getCitationProvider();
        return (configured != null && !configured.isBlank()) ? configured : "stub";
    }
}
