-- V27__citation_status_v7.sql
-- v7 Stage 3 citation check statuses: NOT_FOUND (citation text removed from the draft, warn flag)
-- and NOT_CHECKED (token missing / call cap reached / API error, block flag). Without these the
-- citations insert in DraftingWorkflow violates citations_status_check and rolls back the draft.

ALTER TABLE citations DROP CONSTRAINT IF EXISTS citations_status_check;
ALTER TABLE citations
    ADD CONSTRAINT citations_status_check
    CHECK (status IN ('VERIFIED', 'VERIFIED_PARTIAL', 'UNVERIFIED', 'STRIPPED', 'NOT_FOUND', 'NOT_CHECKED'));
