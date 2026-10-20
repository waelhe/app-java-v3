-- Support the provider-owned catalog roster's stable keyset ordering.
-- created_at is non-null on BaseEntity and id is the unique tie-breaker.
-- Build concurrently so this index does not block live catalog writes.
CREATE INDEX CONCURRENTLY idx_products_provider_created_keyset
    ON products (provider_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;
