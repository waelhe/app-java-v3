-- Keyset sort indexes for the bounded provider-owned product connection.
-- Each index matches the equality owner predicate followed by one whitelisted
-- non-null sort key and the stable unique id tie-breaker. The price index includes
-- currency because minor-unit values from different currencies are incomparable.
-- Build concurrently so this does not block live catalog writes.
CREATE INDEX CONCURRENTLY idx_products_provider_title_keyset
    ON products (provider_id, title DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX CONCURRENTLY idx_products_provider_price_keyset
    ON products (provider_id, currency, price_minor DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX CONCURRENTLY idx_products_provider_status_keyset
    ON products (provider_id, product_status DESC, id DESC)
    WHERE is_deleted = FALSE;
