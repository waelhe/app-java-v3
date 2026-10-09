-- Community post Arabic full-text index; isolated so a failed concurrent
-- build cannot leave a second index from this migration completed already.
-- Follow repository precedent V51/V67/V80/V81:
-- * CREATE INDEX CONCURRENTLY avoids blocking ordinary writes.
-- * V168__community_post_search_fts_concurrently.sql.conf opts out of
--   migration transactions; application.yml sets Flyway's PostgreSQL lock
--   to session scope, as required by the repository's official pattern.
-- If PostgreSQL leaves this index INVALID after a failure, drop this index,
-- run flyway repair as documented, then retry. Do not use IF NOT EXISTS:
-- that could silently keep an invalid index.
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_fts
    ON neighborhood_posts USING gin (
        to_tsvector('arabic', coalesce(title, '') || ' ' || coalesce(body, ''))
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
