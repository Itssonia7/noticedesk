-- V25__draft_flags.sql
-- Architecture v7 Stage 2: Code checks and draft flags table

CREATE TABLE IF NOT EXISTS draft_flags (
    flag_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    notice_id   UUID NOT NULL REFERENCES notices (notice_id) ON DELETE CASCADE,
    draft_id    UUID REFERENCES drafts (draft_id) ON DELETE CASCADE,
    check_name  VARCHAR(100) NOT NULL,
    severity    VARCHAR(20) NOT NULL DEFAULT 'warn' CHECK (severity IN ('block', 'warn', 'info')),
    passed      BOOLEAN NOT NULL,
    details     JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolved_by TEXT,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_draft_flags_tenant ON draft_flags (tenant_id);
CREATE INDEX IF NOT EXISTS idx_draft_flags_notice ON draft_flags (notice_id);
CREATE INDEX IF NOT EXISTS idx_draft_flags_draft ON draft_flags (draft_id);

-- Apply RLS and app role permissions following V9 and V12 patterns
ALTER TABLE draft_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE draft_flags FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON draft_flags;
CREATE POLICY tenant_isolation ON draft_flags FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON draft_flags TO noticedesk_app;
