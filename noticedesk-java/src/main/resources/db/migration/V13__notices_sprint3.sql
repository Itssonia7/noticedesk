-- 0013_notices_sprint3.sql
-- Sprint 3 additions to the matters + notices tables created in Sprint 1.
--
-- - matters.opening_date now defaults to CURRENT_DATE (was just nullable).
-- - notices gains demand_amount, ingest_channel (NOT NULL), source_inbox_id.
-- - notices.pan_gstin_reconciliation_status CHECK widens to include
--   'no_identifier' for the routing-step E exit when neither PAN nor GSTIN
--   was extractable.
-- - documents_inbox.parsed_to_notice_id was added in 0011 as a FK to
--   notices(notice_id); kept as-is.

ALTER TABLE matters
    ALTER COLUMN opening_date SET DEFAULT CURRENT_DATE;

ALTER TABLE notices
    ADD COLUMN IF NOT EXISTS demand_amount    NUMERIC,
    ADD COLUMN IF NOT EXISTS ingest_channel   TEXT,
    ADD COLUMN IF NOT EXISTS source_inbox_id  UUID REFERENCES documents_inbox (inbox_id) ON DELETE SET NULL;

-- Backfill ingest_channel for any pre-Sprint-3 rows so we can NOT NULL it.
UPDATE notices SET ingest_channel = 'web_upload' WHERE ingest_channel IS NULL;

ALTER TABLE notices
    ALTER COLUMN ingest_channel SET NOT NULL;

ALTER TABLE notices DROP CONSTRAINT IF EXISTS notices_ingest_channel_check;
ALTER TABLE notices
    ADD CONSTRAINT notices_ingest_channel_check CHECK (ingest_channel IN (
        'web_upload', 'mobile_capture', 'email',
        'gst_portal_gsp', 'it_portal_aa', 'whatsapp'
    ));

-- Widen the pan_gstin_reconciliation_status check to include 'no_identifier'.
ALTER TABLE notices DROP CONSTRAINT IF EXISTS notices_pan_gstin_reconciliation_status_check;
ALTER TABLE notices
    ADD CONSTRAINT notices_pan_gstin_reconciliation_status_check
    CHECK (pan_gstin_reconciliation_status IN (
        'reconciled', 'pending', 'mismatch_blocked', 'no_identifier'
    ));

CREATE INDEX IF NOT EXISTS idx_notices_source_inbox ON notices (source_inbox_id);
CREATE INDEX IF NOT EXISTS idx_notices_document_type ON notices (tenant_id, document_type)
    WHERE document_type IS NOT NULL;

-- documents_inbox gets the parsed-agent JSON output so the inbox UI can show
-- parse details even before (or instead of) a notice row being created.
ALTER TABLE documents_inbox
    ADD COLUMN IF NOT EXISTS raw_parsed_json JSONB;
