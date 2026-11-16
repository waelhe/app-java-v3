-- A-17 (compliance plan C.7 — the M1 store root, «جذر المتجر»): the store
-- categories dictionary + the Product entity + the media line's third
-- target column. Three landings, one migration (the unit's own scope).
--
-- 1) store_categories: «قاموس فئات المتجر (بياناتًا لا ترحيلات)» — the V70
--    listing-category registry discipline VERBATIM, with one honest
--    difference: V70 seeded the platform's code-documented starter value
--    ("stay"); the store vocabulary has NO code-documented value, so this
--    migration seeds NOTHING — the rows arrive as data operations (the
--    plan's own wording: the dictionary is data, not migrations).
-- 2) products: the M1 root — the authoritative pricing record the orders
--    line's cart snapshots point at («التسعير المرجعي يحل محل مصدر المبالغ
--    لا مسار اللقطة» — the A-11 recorded boundary: the cart's frozen
--    snapshot stays the order's own truth) and the media line's third
--    target. store_category_code references the dictionary by its stable
--    key with a REAL FK — both tables land together, so unlike V70's
--    declared-debt FK (legacy free-text rows predated its registry), this
--    one is enforceable from day one. provider_id is a plain UUID column
--    with no FK (the V32/V48/V52/V54/V60/V61/V64 cross-module discipline
--    verbatim — the ProductLookupPort seam resolves it).
-- 3) media_assets.product_id: the V76 target-generalization widening, the
--    third arm — owner_kind gains PRODUCT, the exactly-one-target CHECK
--    gains its third leg, the aud mirror gains the column (V24 convention;
--    the V33 lesson: base-table columns without the _aud twin break audit
--    INSERTs silently).
--
-- Checksum registered in migration-checksums.properties in this same unit
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- 1) The store categories dictionary (V70 verbatim, minus the seed).
CREATE TABLE store_categories (
    id         UUID PRIMARY KEY,
    code       VARCHAR(50) NOT NULL,
    name_en    VARCHAR(100),
    name_ar    VARCHAR(100),
    position   INT NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_store_categories_position_nonnegative CHECK (position >= 0) NOT VALID,
    CONSTRAINT chk_store_categories_code_shape
        CHECK (code = lower(code) AND length(code) BETWEEN 1 AND 50
               AND code ~ '^[a-z0-9-]+$')
        NOT VALID
);

-- Code uniqueness is GLOBAL (the V70 identity stance verbatim): the code is
-- the dictionary's identity — the value the products FK references. Identity
-- values are never recycled: a soft-deleted row keeps its code reserved.
CREATE UNIQUE INDEX uq_store_categories_code
    ON store_categories (code);

ALTER TABLE store_categories VALIDATE CONSTRAINT chk_store_categories_position_nonnegative;
ALTER TABLE store_categories VALIDATE CONSTRAINT chk_store_categories_code_shape;

-- The storefront display-order read.
CREATE INDEX idx_store_categories_position ON store_categories (position) WHERE is_deleted = FALSE;

-- 2) The M1 store root.
CREATE TABLE products (
    id                  UUID PRIMARY KEY,
    store_category_code VARCHAR(50) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    description         VARCHAR(2000),
    price_minor         BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    provider_id         UUID NOT NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_products_store_category
        FOREIGN KEY (store_category_code) REFERENCES store_categories (code),
    CONSTRAINT chk_products_price_nonnegative CHECK (price_minor >= 0),
    CONSTRAINT chk_products_currency_shape
        CHECK (currency = upper(currency) AND length(currency) = 3
               AND currency ~ '^[A-Z]{3}$')
);

-- The provider's own roster read (the M1 owner read; the public storefront
-- surfaces arrive with the M2 wave, C.8).
CREATE INDEX idx_products_provider ON products (provider_id) WHERE is_deleted = FALSE;

-- The media target seam's resolution (the ProductLookupPort read).
CREATE INDEX idx_products_id_lookup ON products (id) WHERE is_deleted = FALSE;

-- 3) The media line's third target (the V76 widening's third arm).
ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS product_id UUID;

-- The discriminator vocabulary gains PRODUCT (drop + recreate: the V61
-- CHECK membership-guard pattern for vocabulary widenings).
ALTER TABLE media_assets
    DROP CONSTRAINT IF EXISTS ck_media_assets_owner_kind;
ALTER TABLE media_assets
    ADD CONSTRAINT ck_media_assets_owner_kind
        CHECK (owner_kind IN ('LISTING', 'POST', 'PRODUCT'));

ALTER TABLE media_assets
    DROP CONSTRAINT IF EXISTS ck_media_assets_one_target;
ALTER TABLE media_assets
    ADD CONSTRAINT ck_media_assets_one_target
        CHECK (
            (owner_kind = 'LISTING' AND listing_id IS NOT NULL AND post_id IS NULL AND product_id IS NULL)
            OR
            (owner_kind = 'POST' AND post_id IS NOT NULL AND listing_id IS NULL AND product_id IS NULL)
            OR
            (owner_kind = 'PRODUCT' AND product_id IS NOT NULL AND listing_id IS NULL AND post_id IS NULL)
        );

-- The owner-gated product read's one scan (the V76 feed-index shape).
CREATE INDEX idx_media_assets_product_feed
    ON media_assets (product_id, position)
    WHERE product_id IS NOT NULL AND is_deleted = FALSE AND status = 'UPLOADED';

-- Envers audit mirrors (the V24 convention; the V33 lesson).
CREATE TABLE store_categories_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    code       VARCHAR(50),
    name_en    VARCHAR(100),
    name_ar    VARCHAR(100),
    position   INT,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE products_aud (
    id                  UUID NOT NULL,
    rev                 INTEGER NOT NULL,
    revtype             SMALLINT,
    store_category_code VARCHAR(50),
    title               VARCHAR(200),
    description         VARCHAR(2000),
    price_minor         BIGINT,
    currency            VARCHAR(3),
    provider_id         UUID,
    is_deleted          BOOLEAN,
    version             BIGINT,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ,
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

ALTER TABLE media_assets_aud
    ADD COLUMN IF NOT EXISTS product_id UUID;
