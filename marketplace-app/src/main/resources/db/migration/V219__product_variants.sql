-- ADR-0012 (the merged Saleor study's Phase 2, PR #602 §7): the
-- merchandise model's variant depth — the purchasable unit under a
-- product. Additive only: a NEW table, zero changes to products or any
-- other existing object — a product with no variants keeps selling
-- exactly as today (the ADR's zero-variant rule).
--
-- Numbering note: V215–V218 are claimed by the in-flight delivery
-- vertical slice (PR #604's branch) — this migration takes V219 so the
-- two open tracks never collide in flyway_schema_history regardless of
-- merge order.
--
-- 1) product_variants: the purchasable unit — own SKU, own authoritative
--    price (the product's currency, denormalized at creation exactly like
--    provider_id — the V184 orders.seller_id precedent: the same-module
--    write path maintains it, never the client), own shelf and reserved
--    counters (the V184 CHECK family verbatim), own availability
--    lifecycle (the V184 membership set).
--    product_id carries a REAL FK — both tables land in the same module
--    (marketplace-catalog) and the parent predates the child, so the
--    reference is enforceable from day one (the V116 store_category FK
--    stance; NOT the V32 cross-module no-FK rule — this is intra-module).
CREATE TABLE product_variants (
    id                 UUID PRIMARY KEY,
    product_id         UUID NOT NULL,
    provider_id        UUID NOT NULL,
    sku                VARCHAR(100) NOT NULL,
    name               VARCHAR(200),
    price_minor        BIGINT NOT NULL,
    currency           VARCHAR(3) NOT NULL,
    stock_quantity     INT NOT NULL DEFAULT 0,
    reserved_quantity  INT NOT NULL DEFAULT 0,
    variant_status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_product_variants_product
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_product_variants_price_nonnegative CHECK (price_minor >= 0),
    CONSTRAINT chk_product_variants_currency_shape
        CHECK (currency = upper(currency) AND length(currency) = 3
               AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_product_variants_sku_shape
        CHECK (length(btrim(sku)) BETWEEN 1 AND 100),
    CONSTRAINT chk_product_variants_stock_nonnegative    CHECK (stock_quantity    >= 0),
    CONSTRAINT chk_product_variants_reserved_nonnegative CHECK (reserved_quantity >= 0),
    CONSTRAINT chk_product_variants_reserved_within_stock CHECK (reserved_quantity <= stock_quantity),
    CONSTRAINT chk_product_variants_status
        CHECK (variant_status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'))
);

-- SKU uniqueness is PER PROVIDER (the ADR's seller-namespace decision):
-- two sellers may both use RED-XL; one seller's namespace is unique
-- forever. GLOBAL (not partial on is_deleted) — a soft-deleted variant
-- keeps its SKU reserved (the V70 identity stance: identity values are
-- never recycled).
CREATE UNIQUE INDEX uq_product_variants_provider_sku
    ON product_variants (provider_id, sku);

-- The product's variant roster read (the owner's list surface).
CREATE INDEX idx_product_variants_product
    ON product_variants (product_id) WHERE is_deleted = FALSE;

-- 2) The Envers audit mirror (the V24 convention; the V33 lesson: a base
--    table without its _aud twin breaks audit INSERTs silently).
CREATE TABLE product_variants_aud (
    id                 UUID NOT NULL,
    rev                INTEGER NOT NULL,
    revtype            SMALLINT,
    product_id         UUID,
    provider_id        UUID,
    sku                VARCHAR(100),
    name               VARCHAR(200),
    price_minor        BIGINT,
    currency           VARCHAR(3),
    stock_quantity     INT,
    reserved_quantity  INT,
    variant_status     VARCHAR(16),
    is_deleted         BOOLEAN,
    version            BIGINT,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
