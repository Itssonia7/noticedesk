-- V28__provisional_templates.sql
-- v7: an approved AI issue section can be promoted into a provisional issue card + reply template
-- (ProvisionalTemplateService). Provenance columns record where the card / template came from, and
-- ai_issue_sections records what it was promoted into (idempotency: a second promotion returns it).
--
-- issue_cards and reply_templates are the global catalogue (no tenant_id, no RLS; same as V23 / V24):
-- a promoted template is shared by every tenant of the deployment, which is why promotion replaces
-- every notice-specific value (names, amounts, references, dates, periods) with placeholders before
-- the row is written. The provenance ids are opaque UUIDs; ai_issue_sections itself stays
-- tenant-scoped under its V26 RLS policy.

ALTER TABLE issue_cards ADD COLUMN IF NOT EXISTS source_ai_section_id UUID;
ALTER TABLE issue_cards ADD COLUMN IF NOT EXISTS source_notice_id     UUID;
ALTER TABLE issue_cards ADD COLUMN IF NOT EXISTS source_tenant_id     UUID;

ALTER TABLE reply_templates ADD COLUMN IF NOT EXISTS source_ai_section_id UUID;
ALTER TABLE reply_templates ADD COLUMN IF NOT EXISTS source_notice_id     UUID;
ALTER TABLE reply_templates ADD COLUMN IF NOT EXISTS source_tenant_id     UUID;

CREATE INDEX IF NOT EXISTS idx_issue_cards_source_ai_section ON issue_cards (source_ai_section_id);
CREATE INDEX IF NOT EXISTS idx_reply_templates_source_ai_section ON reply_templates (source_ai_section_id);

-- promotion record on the AI section (tenant-scoped, RLS from V26 applies)
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_card_id          VARCHAR(50);
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_card_version     INT;
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_template_id      VARCHAR(50);
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_template_version INT;
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_by               TEXT;
ALTER TABLE ai_issue_sections ADD COLUMN IF NOT EXISTS promoted_at               TIMESTAMPTZ;
