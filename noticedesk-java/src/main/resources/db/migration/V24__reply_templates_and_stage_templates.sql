-- V24__reply_templates_and_stage_templates.sql
-- Architecture v7 Stage 2: Reply templates and stage templates catalog

-- 1. reply_templates table (Global, no tenant_id)
CREATE TABLE IF NOT EXISTS reply_templates (
    template_id                 VARCHAR(50) NOT NULL,
    version                     INT NOT NULL DEFAULT 1,
    card_id                     VARCHAR(50) NOT NULL,
    effective_from              DATE,
    effective_to                DATE,
    status                      VARCHAR(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'provisional', 'active', 'retired')),
    blocks                      JSONB NOT NULL DEFAULT '[]'::jsonb,
    variables                   JSONB NOT NULL DEFAULT '[]'::jsonb,
    documents                   JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary_line                TEXT,
    procedural_objection_options JSONB NOT NULL DEFAULT '[]'::jsonb,
    approved_by                 TEXT,
    approved_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (template_id, version)
);

CREATE INDEX IF NOT EXISTS idx_reply_templates_card_id ON reply_templates (card_id);
CREATE INDEX IF NOT EXISTS idx_reply_templates_status ON reply_templates (status);

-- 2. stage_templates table (Global, no tenant_id)
CREATE TABLE IF NOT EXISTS stage_templates (
    stage                       VARCHAR(50) NOT NULL,
    version                     INT NOT NULL DEFAULT 1,
    effective_from              DATE,
    effective_to                DATE,
    status                      VARCHAR(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'provisional', 'active', 'retired')),
    sections                    JSONB NOT NULL DEFAULT '{}'::jsonb,
    approved_by                 TEXT,
    approved_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (stage, version)
);

CREATE INDEX IF NOT EXISTS idx_stage_templates_stage ON stage_templates (stage);
CREATE INDEX IF NOT EXISTS idx_stage_templates_status ON stage_templates (status);

-- Permissions for noticedesk_app
GRANT SELECT, INSERT, UPDATE, DELETE ON reply_templates TO noticedesk_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON stage_templates TO noticedesk_app;
