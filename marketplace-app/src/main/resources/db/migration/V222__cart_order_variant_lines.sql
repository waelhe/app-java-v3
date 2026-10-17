-- ADR-0012 (the merchandise model's variant depth — the cart-integration
-- slice): the cart and order lines learn WHICH VARIANT they carry. The
-- Saleor checkout model adopted verbatim: a cart line may reference a
-- purchasable variant (its own authoritative price and its own shelf);
-- a line with NO variant keeps buying the product-level record exactly as
-- today (the ADR's zero-variant rule — no fake variant is ever invented
-- for a unique item).
--
-- Shape decisions (each grounded):
--   * variant_id is NULLABLE and carries NO foreign key — the V32
--     cross-module no-FK rule (cart_items/order_items live in
--     marketplace-orders, product_variants in marketplace-catalog; the
--     module never reads catalog's tables — the reference is opaque,
--     exactly like product_id beside it).
--   * THE UNION KEY WIDENS: the old partial uniques
--     uq_cart_items_cart_product (cart_id, product_id) and
--     uq_order_items_order_product (order_id, product_id) collapse a
--     duplicate add into the quantity bump — under variants, two
--     DIFFERENT variants of one product must coexist as separate lines
--     while the product-level line keeps collapsing. PostgreSQL unique
--     indexes treat NULLs as distinct, so a plain (cart_id, product_id,
--     variant_id) key would STOP collapsing product-level duplicates
--     (two NULL-variant rows never collide). The house answer is the
--     documented COALESCE-to-zero-UUID expression key:
--       (cart_id, product_id, COALESCE(variant_id, '000…000'::uuid))
--     — NULL maps to the zero UUID, so product-level lines keep the
--     exact union semantics they had, and each variant is its own line.
--   * CONTINUOUS ENFORCEMENT (the V81 deploy-overlap lesson, tightened):
--     the widened indexes are built CONCURRENTLY under temporary names
--     FIRST (the old indexes' data satisfies them by construction — any
--     set unique under (cart, product) is unique under the finer key),
--     then the swap (DROP old, RENAME new) is catalog-only — no scan, no
--     build window without enforcement. The script runs
--     NON-TRANSACTIONALLY (the .conf sibling, the V81/V105 pattern):
--     PostgreSQL cannot run CREATE INDEX CONCURRENTLY inside a
--     transaction block, and spring.flyway.postgresql.transactional-lock
--     is already false (in place since V51).
--   * RETRY SAFETY (the CodeRabbit-measured correction, adopted): a
--     FAILED concurrent build enters the catalogs as an INVALID index
--     BEFORE its scans (PostgreSQL, "Building Indexes Concurrently" —
--     the documented recovery is drop and retry), so IF NOT EXISTS on
--     the builds is exactly wrong for the retry: the name exists, the
--     skip fires, and the swap would RENAME the broken index into
--     place. The temp indexes are therefore dropped UNCONDITIONALLY by
--     name before each build (a valid one from a partially-succeeded
--     run is simply rebuilt; an INVALID one is the documented recovery)
--     — deterministic drop → build → swap on every execution. The ALTERs
--     keep their IF NOT EXISTS idempotence (plain catalog writes), and
--     the swap's DROP/RENAME stay IF EXISTS.
--   * The Envers mirrors gain the column the same way (the V24
--     convention; the V33 lesson) — nullable in the mirror, as every
--     added column is.
--
-- Numbering note: V215–V218 are claimed by the in-flight delivery slice
-- (PR #604), V219 by the variant model (PR #613 — this branch's own
-- parent), and V220/V221 by the commission-freeze slice (ADR-0013 D2);
-- this slice takes V222 so the four open tracks never collide in
-- flyway_schema_history regardless of merge order.

-- ---------- the columns (idempotent, plain ALTERs) ----------------------

ALTER TABLE cart_items     ADD COLUMN IF NOT EXISTS variant_id UUID;
ALTER TABLE cart_items_aud ADD COLUMN IF NOT EXISTS variant_id UUID;
ALTER TABLE order_items     ADD COLUMN IF NOT EXISTS variant_id UUID;
ALTER TABLE order_items_aud ADD COLUMN IF NOT EXISTS variant_id UUID;

-- ---------- the widened union keys: build first, swap after -------------

-- The retry's own prelude (the INVALID-leftover recovery above): drop the
-- temp names unconditionally, then build — never IF NOT EXISTS, never a
-- skipped build over a broken catalog entry.
DROP INDEX CONCURRENTLY IF EXISTS uq_cart_items_cart_product_v222;
DROP INDEX CONCURRENTLY IF EXISTS uq_order_items_order_product_v222;

CREATE UNIQUE INDEX CONCURRENTLY uq_cart_items_cart_product_v222
    ON cart_items (cart_id, product_id,
                   COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE is_deleted = FALSE;

CREATE UNIQUE INDEX CONCURRENTLY uq_order_items_order_product_v222
    ON order_items (order_id, product_id,
                    COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE is_deleted = FALSE;

-- The swap: catalog-only (no table scan — the concurrent builds above did
-- the verification), so the window without a unique guard is the
-- milliseconds the catalog locks hold, and the OLD deployment cannot
-- write variant rows at all (it has no variant path — its product-level
-- writes keep satisfying the new key exactly as they satisfied the old).
DROP INDEX IF EXISTS uq_cart_items_cart_product;
DROP INDEX IF EXISTS uq_order_items_order_product;
ALTER INDEX IF EXISTS uq_cart_items_cart_product_v222  RENAME TO uq_cart_items_cart_product;
ALTER INDEX IF EXISTS uq_order_items_order_product_v222 RENAME TO uq_order_items_order_product;
