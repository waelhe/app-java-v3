-- Keyset sort indexes for the bounded provider-owned product connection.
-- Each index matches the equality owner predicate followed by one whitelisted
-- non-null sort key and the stable unique id tie-breaker. Create concurrently
-- because products is live data and ordinary writes must continue during rollout.
CREATE INDEX CONCURRENTLY idx_products_provider_title_keyset
    ON products (provider_id, title DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX CONCURRENTLY idx_products_provider_price_keyset
    ON products (provider_id, price_minor DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX CONCURRENTLY idx_products_provider_status_keyset
    ON products (provider_id, product_status DESC, id DESC)
    WHERE is_deleted = FALSE;
