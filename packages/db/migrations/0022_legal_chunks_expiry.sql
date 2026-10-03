-- 0022_legal_chunks_expiry.sql
-- Adds saved_at, effective_date, and expires_at columns to legal_chunks with index and backfill logic.

ALTER TABLE legal_chunks 
    ADD COLUMN IF NOT EXISTS saved_at TIMESTAMPTZ DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS effective_date TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_legal_chunks_expires_at ON legal_chunks (expires_at);

-- 1. Backfill saved_at from created_at where missing
UPDATE legal_chunks 
SET saved_at = created_at 
WHERE saved_at IS NULL;

-- 2. Backfill expires_at:
-- EXEMPTION (Indefinite / NULL expires_at): Acts, Rules, Court Judgments, and Core GST Corpus
UPDATE legal_chunks 
SET expires_at = NULL 
WHERE expires_at IS NULL 
  AND (
    act_or_circular ILIKE '%Act%' 
    OR act_or_circular ILIKE '%Rules%' 
    OR act_or_circular ILIKE '%Court%' 
    OR act_or_circular ILIKE '%Tribunal%' 
    OR act_or_circular IN (
      'Supreme Court of India', 'Calcutta High Court', 'Madras High Court', 
      'Bombay High Court', 'Delhi High Court', 'Gujarat High Court', 
      'Karnataka High Court', 'Kerala High Court', 'Allahabad High Court', 
      'Telangana High Court', 'Andhra Pradesh High Court', 'Punjab & Haryana High Court', 
      'Rajasthan High Court', 'Madhya Pradesh High Court', 'Patna High Court', 
      'Orissa High Court', 'Gauhati High Court', 'Jharkhand High Court', 
      'Chhattisgarh High Court', 'Himachal Pradesh High Court', 'Jammu & Kashmir High Court',
      'GST_CORPUS'
    )
  );

-- 3. Set 12-month TTL for remaining auto-cached Opus templates and circulars
UPDATE legal_chunks 
SET expires_at = created_at + INTERVAL '12 months' 
WHERE expires_at IS NULL;
