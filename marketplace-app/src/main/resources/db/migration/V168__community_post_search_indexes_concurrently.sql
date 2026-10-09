-- Community post search indexes use the existing PostgreSQL engines first.
-- Arabic full-text search is the primary retrieval path; pg_trgm is the
-- typo-tolerant fallback (only when FTS has zero total matches).
--
-- Both are built CONCURRENTLY following the repository's V51/V67/V80/V81
-- pattern. The sibling .conf and project-level
-- spring.flyway.postgresql.transactional-lock=false are required together.
-- If a concurrent build fails, Flyway fails loudly and PostgreSQL may leave
-- an INVALID index; drop that invalid index, run flyway repair as documented,
-- and retry. Do not use IF NOT EXISTS: it can silently leave an invalid index.
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_fts
    ON neighborhood_posts USING gin (
        to_tsvector('arabic', coalesce(title, '') || ' ' || coalesce(body, ''))
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';

CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_trgm
    ON neighborhood_posts USING gin (
        (coalesce(title, '') || ' ' || coalesce(body, '')) gin_trgm_ops
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
