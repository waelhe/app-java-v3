-- V303__products_title_keyset_indexes.sql
-- The 2026-11-06 #808 round review (Minor · Performance & Scalability):
-- the product scrolls' keyset sorts address (title, id) — the ADMIN
-- population's findAllByOrderByTitleAscIdAsc/DescIdDesc and the public
-- ACTIVE slice's status-filtered twins — but every existing title index
-- is PREFIXED by provider_id (V247's provider-scoped keyset pair), and
-- those queries never constrain the provider: every page fell back to a
-- full sort of the live population. Two covering b-trees serve the four
-- scrolls exactly — (title, id) for the ADMIN population, (product_
-- status, title, id) for the status-filtered public slice. title is
-- NOT NULL (V116); the id tiebreak rides the keyset's own shape.
-- Non-concurrent (Flyway's transactional DDL — the V298 precedent; the
-- CONCURRENTLY exceptions are reserved for locks-conflicting online
-- changes like V211/V247, per their own recorded rationale).
CREATE INDEX IF NOT EXISTS idx_products_title_id_keyset
    ON products (title, id);
CREATE INDEX IF NOT EXISTS idx_products_status_title_id_keyset
    ON products (product_status, title, id);
