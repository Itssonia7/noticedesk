package com.noticedesk.api.service.learned;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One entry of the learned library: an issue the new-issue writer drafted with no catalogue card (status none, a
 * substantive ground), saved as {@code $PRIVATE_SEED_DIR/learned/<yyyy-mm-dd>_<notice-id>_issue<N>.json}. It holds
 * notice text fragments, so it lives in the private folder only, never in the catalogue files or the repository.
 *
 * <p>{@code status} is "provisional" when written and "approved" once a named person approved it
 * ({@code approved_by}); only approved entries can reach the matcher (see {@link LearnedLibrary}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearnedEntry(
        @JsonProperty("entry_id") String entryId,
        @JsonProperty("notice_id") String noticeId,
        @JsonProperty("issue_no") int issueNo,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("document_type") String documentType,
        /** Response stage the input document calls for ("reply", "appeal", ...), else the stage template name. */
        @JsonProperty("stage") String stage,
        /** Stage template the draft used (e.g. scn_73). */
        @JsonProperty("stage_template") String stageTemplate,
        /** The matcher's ground text for the issue. */
        @JsonProperty("ground") String ground,
        @JsonProperty("legal_section") String legalSection,
        @JsonProperty("amount") Object amount,
        @JsonProperty("period") String period,
        /** Section 04 text as stored in ai_issue_sections (sanitised HTML, NOT_FOUND citations removed). */
        @JsonProperty("grounds_of_defence") String groundsOfDefence,
        /** Section 06 text as stored in ai_issue_sections. */
        @JsonProperty("legal_submissions") String legalSubmissions,
        /** Section 10 documents the writer listed. */
        @JsonProperty("documents") List<String> documents,
        @JsonProperty("summary_line") String summaryLine,
        /** Each citation: case, court, year, cited_for, check_status (VERIFIED / NOT_FOUND / NOT_CHECKED ...). */
        @JsonProperty("citations") List<Map<String, Object>> citations,
        /** provider:model of the writer. */
        @JsonProperty("writer") String writer,
        @JsonProperty("prompt_version") String promptVersion,
        /** The notice's own values (client name, GSTIN, reference) replaced by placeholders when the text is reused. */
        @JsonProperty("notice_facts") Map<String, Object> noticeFacts,
        @JsonProperty("status") String status,
        @JsonProperty("approved_by") String approvedBy,
        @JsonProperty("approved_at") String approvedAt,
        /** Commit sha (or run id) of the code that wrote the entry. */
        @JsonProperty("source_run") String sourceRun
) {
    public static final String STATUS_PROVISIONAL = "provisional";
    public static final String STATUS_APPROVED = "approved";

    public LearnedEntry {
        documents = documents != null ? List.copyOf(documents) : List.of();
        citations = citations != null ? List.copyOf(citations) : List.of();
        noticeFacts = noticeFacts != null ? noticeFacts : Map.of();
        if (status == null || status.isBlank()) status = STATUS_PROVISIONAL;
    }

    @JsonIgnore
    public boolean isApproved() {
        return STATUS_APPROVED.equalsIgnoreCase(status) && approvedBy != null && !approvedBy.isBlank();
    }

    /** This entry approved by {@code by} at {@code at} (ISO-8601). */
    public LearnedEntry approved(String by, String at) {
        return new LearnedEntry(entryId, noticeId, issueNo, createdAt, documentType, stage, stageTemplate, ground,
                legalSection, amount, period, groundsOfDefence, legalSubmissions, documents, summaryLine, citations, writer,
                promptVersion, noticeFacts, STATUS_APPROVED, by, at, sourceRun);
    }

    /** This entry under another id (a re-run that must not overwrite an approved entry of the same file name). */
    LearnedEntry withEntryId(String id) {
        return new LearnedEntry(id, noticeId, issueNo, createdAt, documentType, stage, stageTemplate, ground,
                legalSection, amount, period, groundsOfDefence, legalSubmissions, documents, summaryLine, citations, writer,
                promptVersion, noticeFacts, status, approvedBy, approvedAt, sourceRun);
    }
}
