-- Community typo-tolerant index; isolated from the Arabic FTS index so
-- either migration can be recovered and retried independently.
-- Follow repository precedent V51/V67/V80/V81. This migration must run
-- outside a transaction for PostgreSQL CREATE INDEX CONCURRENTLY.
-- If PostgreSQL leaves this index INVALID after a failure, drop this index,
-- run flyway repair as documented, then retry. Do not use IF NOT EXISTS.
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_trgm
    ON neighborhood_posts USING gin (
        (coalesce(title, '') || ' ' || coalesce(body, '')) gin_trgm_ops
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
