-- Community typo-tolerant index; isolated from the Arabic FTS index so
-- either migration can be recovered and retried independently.
-- Follow repository precedent V51/V67/V80/V81. This migration must run
-- outside a transaction for PostgreSQL CREATE INDEX CONCURRENTLY.
-- Failure recovery (PostgreSQL CREATE INDEX CONCURRENTLY docs — see V168
-- for the full three-path protocol): an INVALID index is dropped and the
-- migration retried; a VALID index with NO successful V169 row in
-- flyway_schema_history is dropped too (a derived GIN search structure
-- riding no constraint — dropping it never loses table data); never use
-- IF NOT EXISTS.
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_trgm
    ON neighborhood_posts USING gin (
        (coalesce(title, '') || ' ' || coalesce(body, '')) gin_trgm_ops
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
