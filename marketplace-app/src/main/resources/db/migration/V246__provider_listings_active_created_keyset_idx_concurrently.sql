-- Support stable keyset continuation for the ACTIVE-only GraphQL services connection.
-- Match the connection's fixed created_at DESC, id DESC order while excluding
-- inactive and soft-deleted rows from the index.
CREATE INDEX CONCURRENTLY idx_provider_listings_active_created_keyset
    ON provider_listings (created_at DESC, id DESC)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;
