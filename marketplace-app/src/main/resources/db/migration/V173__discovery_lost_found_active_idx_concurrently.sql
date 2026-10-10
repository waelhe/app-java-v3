-- The lost-and-found discovery rail's own index; isolated so a failed
-- concurrent build cannot leave a second index from this migration
-- completed already. Follow repository precedent V51/V67/V80/V81/V168/V169:
-- * CREATE INDEX CONCURRENTLY avoids blocking ordinary writes.
-- * V173__discovery_lost_found_active_idx_concurrently.sql.conf opts out of
--   migration transactions; application.yml sets Flyway's PostgreSQL lock
--   to session scope, as required by the repository's official pattern.
-- Failure recovery (PostgreSQL CREATE INDEX CONCURRENTLY docs — see V168
-- for the full three-path protocol): an INVALID index is dropped and the
-- migration retried; a VALID index with NO successful V173 row in
-- flyway_schema_history is dropped too (a derived read-rail index riding
-- no constraint — dropping it never loses table data); never use
-- IF NOT EXISTS.
-- The shape is the rail query's own: the neighborhood scope leads (the
-- V61 feed index's location_id), the rail's recency key follows
-- (updated_at DESC — a state flip re-surfaces the report), and the
-- partial WHERE carries the rail's FULL eligibility set (JT-20/AC-20:
-- ACTIVE lost-and-found, VISIBLE, live rows — the state never widens).
CREATE INDEX CONCURRENTLY idx_neighborhood_posts_lost_found_active
    ON neighborhood_posts (location_id, updated_at DESC)
    WHERE category = 'LOST_FOUND' AND lost_found_state = 'ACTIVE'
      AND is_deleted = FALSE AND status = 'VISIBLE';
