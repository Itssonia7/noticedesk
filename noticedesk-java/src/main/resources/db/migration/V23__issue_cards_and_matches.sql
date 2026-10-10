-- V23__issue_cards_and_matches.sql
-- Architecture v7: Issue cards catalogue and issue matches tables

-- 1. issue_cards table
CREATE TABLE IF NOT EXISTS issue_cards (
    card_id      VARCHAR(50) NOT NULL,
    version      INT NOT NULL DEFAULT 1,
    title        TEXT NOT NULL,
    summary      TEXT NOT NULL,
    sections     JSONB NOT NULL DEFAULT '[]'::jsonb,
    period_from  TEXT,
    period_to    TEXT,
    stages       JSONB NOT NULL DEFAULT '[]'::jsonb,
    keywords     JSONB NOT NULL DEFAULT '[]'::jsonb,
    not_this_if  JSONB NOT NULL DEFAULT '[]'::jsonb,
    template_id  TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'retired')),
    reviewed_by  TEXT,
    reviewed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (card_id, version)
);

CREATE INDEX IF NOT EXISTS idx_issue_cards_status ON issue_cards (status);
CREATE INDEX IF NOT EXISTS idx_issue_cards_card_id ON issue_cards (card_id);

-- 2. issue_matches table
CREATE TABLE IF NOT EXISTS issue_matches (
    match_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    notice_id   UUID NOT NULL REFERENCES notices (notice_id) ON DELETE CASCADE,
    draft_id    UUID REFERENCES drafts (draft_id) ON DELETE SET NULL,
    issue_no    INT NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('full', 'partial', 'none')),
    card_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,
    facts       JSONB NOT NULL DEFAULT '{}'::jsonb,
    why         TEXT,
    flags       JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_issue_matches_tenant ON issue_matches (tenant_id);
CREATE INDEX IF NOT EXISTS idx_issue_matches_notice ON issue_matches (notice_id);

-- Apply RLS and app role permissions following V9 and V12 patterns
ALTER TABLE issue_matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE issue_matches FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON issue_matches;
CREATE POLICY tenant_isolation ON issue_matches FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON issue_matches TO noticedesk_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON issue_cards TO noticedesk_app;

-- 3. Extend api_usage_logs for prompt caching telemetry and notice tracking
ALTER TABLE api_usage_logs ADD COLUMN IF NOT EXISTS step VARCHAR(50);
ALTER TABLE api_usage_logs ADD COLUMN IF NOT EXISTS cache_read_tokens INT DEFAULT 0;
ALTER TABLE api_usage_logs ADD COLUMN IF NOT EXISTS cache_write_tokens INT DEFAULT 0;
ALTER TABLE api_usage_logs ADD COLUMN IF NOT EXISTS notice_id UUID;
