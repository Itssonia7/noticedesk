-- 0018_rag_vector_stores.sql
-- Vector stores for RAG: per-matter evidence chunks and global legal knowledge chunks.

CREATE EXTENSION IF NOT EXISTS "vector";

-- Per-matter evidence chunks (uploaded client files: ledgers, invoices, e-way bills, prior correspondence)
CREATE TABLE IF NOT EXISTS evidence_chunks (
    chunk_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    matter_id           UUID REFERENCES matters (matter_id) ON DELETE CASCADE,
    document_id         UUID REFERENCES documents (document_id) ON DELETE SET NULL,
    document_type       TEXT,
    filename            TEXT,
    chunk_index         INT NOT NULL DEFAULT 0,
    content             TEXT NOT NULL,
    token_count         INT NOT NULL DEFAULT 0,
    embedding           vector(1536),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evidence_chunks_matter ON evidence_chunks (matter_id);
CREATE INDEX IF NOT EXISTS idx_evidence_chunks_embedding ON evidence_chunks USING hnsw (embedding vector_cosine_ops);

-- Global legal knowledge chunks (GST Acts, Rules, CBIC Circulars, Court precedents)
CREATE TABLE IF NOT EXISTS legal_chunks (
    chunk_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    act_or_circular     TEXT NOT NULL,
    section_or_para     TEXT NOT NULL,
    title               TEXT,
    chunk_index         INT NOT NULL DEFAULT 0,
    content             TEXT NOT NULL,
    token_count         INT NOT NULL DEFAULT 0,
    embedding           vector(1536),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_legal_chunks_act ON legal_chunks (act_or_circular);
CREATE INDEX IF NOT EXISTS idx_legal_chunks_embedding ON legal_chunks USING hnsw (embedding vector_cosine_ops);
