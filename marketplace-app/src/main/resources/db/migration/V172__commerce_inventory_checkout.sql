-- V172 — Stage 6 (plan D-08, ADR-0002): commerce inventory + the checkout
-- loop's payment linkage. Additive only: inventory and lifecycle on the
-- catalog root, seller/payment fields on the order machine, the ORDER
-- origin on the EXISTING payment engine — the fit-gap closure without a
-- second engine anywhere.

-- 1. The product's inventory (the store's own stock record).
ALTER TABLE products
    ADD COLUMN stock_quantity     int NOT NULL DEFAULT 0,
    ADD COLUMN reserved_quantity  int NOT NULL DEFAULT 0,
    ADD CONSTRAINT products_stock_nonnegative_chk    CHECK (stock_quantity    >= 0),
    ADD CONSTRAINT products_reserved_nonnegative_chk CHECK (reserved_quantity >= 0),
    ADD CONSTRAINT products_reserved_within_stock_chk CHECK (reserved_quantity <= stock_quantity);

-- 2. The product's display lifecycle (the storefront-visible states).
ALTER TABLE products
    ADD COLUMN product_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT products_status_chk CHECK (product_status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'));
CREATE INDEX idx_products_provider_status ON products (provider_id, product_status);

-- 3. The order machine's commerce fields: the single seller (the ADR-0002
--    cart invariant, stored at placement), the payment linkage and the
--    reservation flag. Cross-module ids are plain UUID columns — no FK
--    across module boundaries (the V32/V48/V52/V54/V60/V61/V64 discipline).
--    seller_id is nullable because legacy orders predate the invariant;
--    every new placement sets it.
ALTER TABLE orders
    ADD COLUMN seller_id         uuid,
    ADD COLUMN payment_intent_id uuid,
    ADD COLUMN stock_reserved    boolean NOT NULL DEFAULT false;

-- 4. The EXISTING payment engine learns the order subject (no parallel
--    engine): a nullable order subject plus its own partial-uniqueness
--    idempotency (one intent per order).
ALTER TABLE payment_intents
    ADD COLUMN order_id uuid;
ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_order_id_unique UNIQUE (order_id);

-- 5. The origin vocabulary widens by exactly one member and its pairing
--    invariant — the V103 CHECK rewritten in place (the same NOT VALID
--    discipline; every existing row satisfies the new shape by construction:
--    ORDER rows are new, legacy rows carry booking_id and no order_id).
ALTER TABLE payment_intents DROP CONSTRAINT chk_payment_intents_origin;
ALTER TABLE payment_intents
    ADD CONSTRAINT chk_payment_intents_origin
        CHECK (origin IN ('BOOKING', 'AD', 'ORDER')) NOT VALID;
ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_origin_pairing;
ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_origin_pairing
        CHECK ((origin = 'BOOKING' AND booking_id IS NOT NULL AND ad_campaign_id IS NULL AND order_id IS NULL)
            OR (origin = 'AD'      AND booking_id IS NULL     AND ad_campaign_id IS NOT NULL AND order_id IS NULL)
            OR (origin = 'ORDER'   AND booking_id IS NULL     AND ad_campaign_id IS NULL AND order_id IS NOT NULL)) NOT VALID;

-- 6. The Envers audit mirrors (the V24 pattern): every @Audited entity
--    column above lands in its audit table in the same migration.
ALTER TABLE products_aud
    ADD COLUMN stock_quantity    int,
    ADD COLUMN reserved_quantity int,
    ADD COLUMN product_status    varchar(16);
ALTER TABLE orders_aud
    ADD COLUMN seller_id         uuid,
    ADD COLUMN payment_intent_id uuid,
    ADD COLUMN stock_reserved    boolean;
ALTER TABLE payment_intents_aud
    ADD COLUMN order_id uuid;
