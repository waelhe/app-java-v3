-- V305__orders_keyset_indexes.sql
-- The 2026-11-09 candidate F2 finding (P3 — Performance & Scalability): the
-- order scrolls' keyset sorts address (placed_at, id), (updated_at, id) and
-- (status, id) — the six Window methods OrderRepository declares
-- (findByOrderBy{PlacedAt,UpdatedAt,Status}{Asc,Desc}Id…, the ADMIN
-- population scrollOrders serves through the `orders` root) — but no
-- existing index carries any of those orderings:
--   * idx_orders_consumer_placed (V113) is PREFIXED by consumer_id, and
--     these queries never constrain the consumer;
--   * the (fulfillment_method, status) pair (V226) answers a different
--     shape entirely.
-- Every page therefore fell back to a full sort of the live population —
-- the exact defect class V303/V304 in this same campaign closed for the
-- product and variant scrolls ("every page fell back to a full sort of the
-- live population"), now closed for the order scrolls the same way.
--
-- Three covering b-trees serve the six scrolls exactly — the
-- single-index-per-direction-pair law (the V303/V304 precedent): the DESC
-- pair walks the same tree backwards (the executor's own bidirectional
-- b-tree scan). placed_at is NOT NULL (V113); updated_at is NOT NULL
-- (V113); status is NOT NULL (V113) — the id tiebreak rides the keyset's
-- own shape. Non-concurrent (Flyway's transactional DDL — the V298/V303/
-- V304 precedent; the CONCURRENTLY exceptions stay reserved for
-- locks-conflicting online changes like V211/V247, per their own recorded
-- rationale).
CREATE INDEX IF NOT EXISTS idx_orders_placed_at_id_keyset
    ON orders (placed_at, id);
CREATE INDEX IF NOT EXISTS idx_orders_updated_at_id_keyset
    ON orders (updated_at, id);
CREATE INDEX IF NOT EXISTS idx_orders_status_id_keyset
    ON orders (status, id);
