-- Migration V26: AI issue sections for Opus output and IndianKanoon citation cache

CREATE TABLE IF NOT EXISTS ai_issue_sections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    notice_id UUID NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    draft_id UUID NOT NULL REFERENCES drafts(id) ON DELETE CASCADE,
    issue_no INT NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    output JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending_review',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_notice ON ai_issue_sections(notice_id);
CREATE INDEX IF NOT EXISTS idx_ai_issue_sections_draft ON ai_issue_sections(draft_id);

-- Enable RLS on ai_issue_sections
ALTER TABLE ai_issue_sections ENABLE ROW LEVEL SECURITY;

CREATE POLICY ai_issue_sections_tenant_isolation ON ai_issue_sections
    FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE IF NOT EXISTS citation_cache (
    normalized_case_name VARCHAR(255) PRIMARY KEY,
    result JSONB NOT NULL,
    ik_doc_id VARCHAR(100),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_citation_cache_checked ON citation_cache(checked_at);
