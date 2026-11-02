-- The shard review's nitpick (Trivial · Performance & Scalability,
-- 2026-11-01 wave 2): the reuse-detection ledger's bounded lifetime.
-- Every completed rotation records the consumed value's SHA-256 (V296);
-- the ON DELETE CASCADE only fires when the family's authorization row
-- dies, and an ACTIVE family rotates forever (each rotation extends the
-- family by REFRESH_TOKEN_TTL — 14 days), so a session rotating every
-- 15 minutes adds ~96 rows a day with no natural bound.
-- The consumed value's reuse-detection meaning expires with the
-- successor it was replaced by: REFRESH_TOKEN_TTL past its consumption
-- the successor is itself expired, so a replay of that value dies at
-- findByToken as an unknown token long before the ledger row matters.
-- The scheduled cleanup (ConsumedRefreshTokenCleanupJob) deletes rows
-- older than that bound; this index serves the deletion predicate.
CREATE INDEX IF NOT EXISTS ix_consumed_refresh_tokens_consumed_at
    ON consumed_refresh_tokens (consumed_at);
