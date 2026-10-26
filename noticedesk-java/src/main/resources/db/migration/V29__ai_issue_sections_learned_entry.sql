-- V29__ai_issue_sections_learned_entry.sql
-- v7 learned library: an AI section written by the new-issue writer for an issue with no catalogue card is also saved
-- as a learned entry in $PRIVATE_SEED_DIR/learned (LearnedLibrary). The DB row mirrors it: the entry id and the run
-- (commit) that wrote it. The learned entry's own fields (ground, section, amount, period, stage, document type,
-- learned status) are in the existing output JSONB. Existing columns are unchanged; status keeps its V26 meaning.

ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS entry_id   VARCHAR(20);
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS source_run VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_entry ON ai_issue_sections (entry_id);
