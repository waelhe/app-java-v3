-- The AI module's withdrawal records — the exact-keyed companion to the
-- public vector index (the CodeRabbit-adopted root fix, 2026-10-09).
--
-- Why a relational row and not a vector-store probe: the withdrawal
-- decision must be an exact keyed lookup. Spring AI's VectorStore interface
-- offers similaritySearch as its only read path, and pgvector applies
-- metadata filters after the approximate HNSW/IVFFlat index scan (the
-- official pgvector repository's filtering notes carry the same caveat),
-- so a filtered topK(1) probe can return no row even though the withdrawn
-- record exists — approximate nearest-neighbor recall must never serve as
-- an existence oracle. A plain B-tree point lookup has no recall semantics.
--
-- House shape (the V107 plain-JDBC precedent): a module-owned table with no
-- JPA entity and therefore no _aud mirror; durable in the same
-- Flyway-managed database as the vector store so it survives restarts and
-- blocks event-publication-registry replays; idempotent replays are the
-- store's INSERT ... ON CONFLICT DO NOTHING at the SQL boundary.
--
-- source_id: the knowledge gateway's source identity — knowledge entry
-- UUIDs (36 chars) today, VARCHAR(64) keeps headroom for other source
-- types without widening migrations. withdrawn_at is observability-only.
--
-- Numbering: V162 — Track B's range (V150-V189).

CREATE TABLE ai_withdrawn_sources (
    source_id    VARCHAR(64) PRIMARY KEY,
    withdrawn_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
