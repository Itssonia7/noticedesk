-- V26__ai_issue_sections_and_citation_cache.sql
-- Architecture v7 Stage 3: AI-written issue sections (saved after the draft row insert, same
-- transaction as draft_flags) and the IndianKanoon citation-check cache.
--
-- NOTE: the first version of this file referenced tenants(id) / notices(id) / drafts(id), which do
-- not exist (the keys are tenant_id / notice_id / draft_id), so it could never have been applied.

CREATE TABLE IF NOT EXISTS ai_issue_sections (
    ai_section_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    notice_id      UUID NOT NULL REFERENCES notices (notice_id) ON DELETE CASCADE,
    draft_id       UUID NOT NULL REFERENCES drafts (draft_id) ON DELETE CASCADE,
    issue_no       INT NOT NULL,
    model          VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    output         JSONB NOT NULL,
    status         VARCHAR(50) NOT NULL DEFAULT 'pending_review',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_tenant ON ai_issue_sections (tenant_id);
CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_notice ON ai_issue_sections (notice_id);
CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_draft ON ai_issue_sections (draft_id);

-- RLS and app role permissions following V9 / V12 (same as V25 draft_flags)
ALTER TABLE ai_issue_sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_issue_sections FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON ai_issue_sections;
CREATE POLICY tenant_isolation ON ai_issue_sections FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON ai_issue_sections TO noticedesk_app;

-- v7 IndianKanoon check cache: public case-law lookups only (no tenant data), shared across tenants.
-- A separate table because V15 already created citation_cache with a different shape (used by the
-- RAG pipeline), so "CREATE TABLE IF NOT EXISTS citation_cache" here would silently do nothing.
CREATE TABLE IF NOT EXISTS ik_citation_cache (
    cache_key     VARCHAR(512) PRIMARY KEY,
    citation_json JSONB NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ik_citation_cache_fetched ON ik_citation_cache (fetched_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON ik_citation_cache TO noticedesk_app;
