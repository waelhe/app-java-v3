-- Support stable keyset continuation for the ACTIVE-only GraphQL services connection.
-- Match the connection's fixed created_at DESC, id DESC order while excluding
-- inactive and soft-deleted rows from the index.
-- Retry-safe form (the CodeRabbit round on #677): a cancelled concurrent
-- build leaves PostgreSQL's INVALID index under this same name and a
-- plain re-run then fails on SQLSTATE 42P07 — dropping first clears the
-- leftover automatically so the retry heals itself (the house's V168/V169
-- documented recovery keeps covering the flyway-repair half this drop
-- cannot automate).
DROP INDEX CONCURRENTLY IF EXISTS idx_provider_listings_active_created_keyset;
CREATE INDEX CONCURRENTLY idx_provider_listings_active_created_keyset
    ON provider_listings (created_at DESC, id DESC)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;
