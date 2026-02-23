-- Extensions required by Spring AI PgVectorStore docs (vector + uuid-ossp; hstore often used by examples).
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS hstore;

-- Mandatory table schema (rag_chunks) with required columns.
-- We satisfy the "file_id" column requirement while still using Spring AI PgVectorStore
-- by generating it from metadata->>'fileId' and storing it (indexed, fast filtering).
CREATE TABLE IF NOT EXISTS rag_chunks (
                                          id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768) NOT NULL,
    file_id varchar GENERATED ALWAYS AS ((metadata ->> 'fileId')) STORED,
    created_at timestamp NOT NULL DEFAULT now()
    );

-- Required indexes
CREATE INDEX IF NOT EXISTS idx_rag_chunks_file_id ON rag_chunks(file_id);

-- Vector index (choose HNSW for low latency; pgvector supports hnsw since newer versions).
-- Note: For cosine distance use vector_cosine_ops.
CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding_hnsw
    ON rag_chunks USING hnsw (embedding vector_cosine_ops);

-- Helpful composite index for time-based cleanup/query patterns (optional but safe)
CREATE INDEX IF NOT EXISTS idx_rag_chunks_created_at ON rag_chunks(created_at);