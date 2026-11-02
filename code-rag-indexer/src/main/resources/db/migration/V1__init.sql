-- ---------------------------------------------------------------------------
-- code-rag initial schema.
--
-- Owned by code-rag-indexer. The MCP server never migrates; it validates.
--
-- Storage model, and why it is shaped this way:
--
--   code_file      the ONLY copy of source text. Chunks store line ranges, not
--                  text, so a file is never stored twice. Snippets and
--                  read_lines are served by slicing this column.
--
--   code_chunk     one row per retrievable unit. Model-agnostic on purpose: it
--                  holds structure (symbol, signature, line range) and the
--                  sparse `lexical` vector, but no embedding.
--
--   code_embedding content-addressed vectors, keyed by the hash of the chunk
--                  text. This is what makes "no duplication" real:
--                    * a chunk whose text is unchanged is never re-embedded;
--                    * identical code in two repositories is embedded once;
--                    * INSERT ... ON CONFLICT DO NOTHING means the Vertex AI
--                      call only happens when the vector is genuinely absent.
--                  No foreign key to code_chunk: a chunk may legitimately have
--                  vectors for several models during a migration window.
--
--   index_run      one row per job execution, so the saving is measurable rather
--                  than asserted.
--
-- ${vector_dimension} is injected by a FlywayConfigurationCustomizer from the
-- configured embedding dimension, so the deterministic test client and Vertex AI
-- share one migration. Changing the dimension invalidates every vector and
-- requires a full re-index.
-- ---------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── Repositories ────────────────────────────────────────────────────────────
CREATE TABLE code_repository
(
    slug            TEXT PRIMARY KEY,
    clone_url       TEXT,
    last_branch     TEXT,
    last_commit_sha TEXT,
    last_indexed_at TIMESTAMPTZ,
    file_count      BIGINT      NOT NULL DEFAULT 0,
    chunk_count     BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Files ───────────────────────────────────────────────────────────────────
CREATE TABLE code_file
(
    repo_slug  TEXT        NOT NULL REFERENCES code_repository (slug) ON DELETE CASCADE,
    path       TEXT        NOT NULL,
    language   TEXT        NOT NULL,
    -- git blob SHA, straight from `git ls-tree`. First-level dedup key: when this
    -- is unchanged the file is not read, chunked or embedded at all.
    blob_sha   TEXT        NOT NULL,
    content    TEXT        NOT NULL,
    line_count INTEGER     NOT NULL,
    commit_sha TEXT        NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repo_slug, path)
);

-- ── Chunks ──────────────────────────────────────────────────────────────────
CREATE TABLE code_chunk
(
    repo_slug      TEXT     NOT NULL,
    path           TEXT     NOT NULL,
    chunk_index    INTEGER  NOT NULL,
    -- SHA-256 of the normalised chunk text: the join key to code_embedding and
    -- the second- and third-level dedup key. TEXT rather than CHAR(64) on
    -- purpose -- CHAR is blank-padded, which makes comparisons against text[]
    -- parameters subtly type-dependent.
    chunk_sha      TEXT     NOT NULL,
    kind           TEXT     NOT NULL,
    symbol         TEXT,
    signature      TEXT,
    language       TEXT     NOT NULL,
    start_line     INTEGER  NOT NULL,
    end_line       INTEGER  NOT NULL,
    token_estimate INTEGER  NOT NULL,
    commit_sha     TEXT     NOT NULL,
    -- Sparse side of hybrid search. A regular column rather than GENERATED,
    -- because the chunk text is not stored -- the indexer computes it from the
    -- text it just embedded.
    lexical        tsvector NOT NULL,
    -- The natural key is the primary key: no surrogate id is needed, and this is
    -- exactly the key the per-file replace deletes on.
    PRIMARY KEY (repo_slug, path, chunk_index),
    CONSTRAINT code_chunk_file_fk FOREIGN KEY (repo_slug, path)
        REFERENCES code_file (repo_slug, path) ON DELETE CASCADE
);

-- ── Vectors ─────────────────────────────────────────────────────────────────
CREATE TABLE code_embedding
(
    chunk_sha  TEXT                       NOT NULL,
    model_id   TEXT                       NOT NULL,
    embedding  vector(${vector_dimension}) NOT NULL,
    created_at TIMESTAMPTZ                NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_sha, model_id)
);

-- ── Run history ─────────────────────────────────────────────────────────────
CREATE TABLE index_run
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo_slug        TEXT        NOT NULL,
    commit_sha       TEXT        NOT NULL,
    branch           TEXT,
    status           TEXT        NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    files_seen       INTEGER     NOT NULL DEFAULT 0,
    files_skipped    INTEGER     NOT NULL DEFAULT 0,
    files_indexed    INTEGER     NOT NULL DEFAULT 0,
    files_deleted    INTEGER     NOT NULL DEFAULT 0,
    chunks_written   INTEGER     NOT NULL DEFAULT 0,
    chunks_embedded  INTEGER     NOT NULL DEFAULT 0,
    chunks_reused    INTEGER     NOT NULL DEFAULT 0,
    error_message    TEXT
);

-- ── Indexes ─────────────────────────────────────────────────────────────────
-- Dense retrieval. Cosine because vectors are L2-normalised on the way in, which
-- makes cosine and inner product rank identically.
CREATE INDEX code_embedding_hnsw
    ON code_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Sparse retrieval: exact identifiers that dense vectors routinely miss.
CREATE INDEX code_chunk_lexical_gin ON code_chunk USING gin (lexical);

-- Fuzzy symbol lookup for find_symbol.
CREATE INDEX code_chunk_symbol_trgm ON code_chunk USING gin (symbol gin_trgm_ops);

-- Metadata scoping, and the per-file delete+reinsert that incremental indexing
-- performs on every changed file.
CREATE INDEX code_chunk_repo_path ON code_chunk (repo_slug, path);
CREATE INDEX code_chunk_sha ON code_chunk (chunk_sha);
CREATE INDEX code_chunk_repo_lang ON code_chunk (repo_slug, language);
CREATE INDEX index_run_repo ON index_run (repo_slug, started_at DESC);
