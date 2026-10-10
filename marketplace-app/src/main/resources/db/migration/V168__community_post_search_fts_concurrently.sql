-- Community post Arabic full-text index; isolated so a failed concurrent
-- build cannot leave a second index from this migration completed already.
-- Follow repository precedent V51/V67/V80/V81:
-- * CREATE INDEX CONCURRENTLY avoids blocking ordinary writes.
-- * V168__community_post_search_fts_concurrently.sql.conf opts out of
--   migration transactions; application.yml sets Flyway's PostgreSQL lock
--   to session scope, as required by the repository's official pattern.
-- Failure recovery (PostgreSQL CREATE INDEX CONCURRENTLY docs: a failed
-- concurrent build leaves the index INVALID, "the recommended recovery
-- method is to drop the index and try again"):
-- 1. INVALID index — drop it, run flyway repair as documented, then retry.
-- 2. VALID index but NO successful V168 row in flyway_schema_history (the
--    DDL committed before the history insert died — executeInTransaction
--    is false here): drop the index too — it is a derived GIN search
--    structure riding no constraint, so dropping it never loses table
--    data — then retry the migration to rebuild it.
-- 3. Never use IF NOT EXISTS: it would silently keep a bad index and
--    mark the migration applied against the wrong state.
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_search_fts
    ON neighborhood_posts USING gin (
        to_tsvector('arabic', coalesce(title, '') || ' ' || coalesce(body, ''))
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
