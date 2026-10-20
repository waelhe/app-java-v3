-- Support the provider-owned catalog roster's stable keyset ordering.
-- created_at is non-null on BaseEntity and id is the unique tie-breaker.
-- Build concurrently so this index does not block live catalog writes.
-- Retry-safe form (the CodeRabbit round on #677): a cancelled concurrent
-- build leaves PostgreSQL's INVALID index under this same name and a
-- plain re-run then fails on SQLSTATE 42P07 — dropping first clears the
-- leftover automatically so the retry heals itself (the house's V168/V169
-- documented recovery keeps covering the flyway-repair half this drop
-- cannot automate).
DROP INDEX CONCURRENTLY IF EXISTS idx_products_provider_created_keyset;
CREATE INDEX CONCURRENTLY idx_products_provider_created_keyset
    ON products (provider_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;
