-- V223 — the Saleor fit-gap study's Phase 3 (PR #602 §7-Phase-3): the
-- commercial rules become EXPLICIT policies. Four policy tables in the
-- pricing module's own schema + the orders' frozen monetary breakdown.
--
-- Ownership law (the study §6, verbatim discipline):
--   * price lists / promotions / vouchers / tax rates live in
--     marketplace-pricing (the pricing domain owns the commercial RULES;
--     the study's «reassess the domain boundary after the required
--     price-list rules are known» — now known, they are pricing's).
--   * the ORDER keeps owning the frozen agreement: subtotal/discount/tax
--     columns + order_price_components rows land in marketplace-orders'
--     tables (the study §3.4: «extend the frozen agreement only when new
--     pricing components are introduced: discount/tax/shipping amounts,
--     currency, relevant rule references»).
--
-- Cross-module references (product_id, order_id) are PLAIN UUIDs with NO
-- foreign keys — the V32 discipline (pricing never reads catalog's tables;
-- orders never reads pricing's). Intra-module references (entries→lists,
-- redemptions→vouchers, components→orders) carry real FKs (the V219
-- intra-module stance).
--
-- Every table mirrors _aud (the AGENTS.md Envers parity rule, V24 pattern).

-- ------------------------------------------------------------------
-- Price lists (currency-scoped, window-bounded override sets)
-- ------------------------------------------------------------------
CREATE TABLE price_lists (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    currency        CHAR(3)      NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT FALSE,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    version         BIGINT       NOT NULL,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ  NOT NULL,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT price_lists_span_chk CHECK (
        starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at
    )
);

CREATE TABLE price_lists_aud (
    id         UUID,
    rev        INTEGER          NOT NULL,
    revtype    SMALLINT         NOT NULL,
    name       VARCHAR(120),
    currency   CHAR(3),
    active     BOOLEAN,
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE TABLE price_list_entries (
    id            UUID PRIMARY KEY,
    price_list_id UUID NOT NULL REFERENCES price_lists(id),
    product_id    UUID NOT NULL,
    variant_id    UUID,
    amount_minor  BIGINT NOT NULL,
    version       BIGINT  NOT NULL,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT price_list_entries_amount_chk CHECK (amount_minor > 0),
    -- one entry per purchasable unit per list (the V222 COALESCE union key:
    -- NULL variant maps to the zero UUID so product-level entries stay unique)
    CONSTRAINT uq_price_list_entries_unit UNIQUE (
        price_list_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)
    )
);

CREATE TABLE price_list_entries_aud (
    id            UUID,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT NOT NULL,
    price_list_id UUID,
    product_id    UUID,
    variant_id    UUID,
    amount_minor  BIGINT,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    is_deleted    BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE INDEX idx_price_list_entries_product ON price_list_entries(product_id)
    WHERE is_deleted = FALSE;

-- ------------------------------------------------------------------
-- Promotions (order-level; one applies per order — the no-stacking law)
-- ------------------------------------------------------------------
CREATE TABLE promotions (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    discount_type   VARCHAR(12)  NOT NULL,
    discount_value  BIGINT       NOT NULL,
    min_spend_minor BIGINT       NOT NULL DEFAULT 0,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    active          BOOLEAN      NOT NULL DEFAULT FALSE,
    version         BIGINT       NOT NULL,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ  NOT NULL,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT promotions_type_chk CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    CONSTRAINT promotions_percentage_chk CHECK (
        discount_type <> 'PERCENTAGE' OR (discount_value > 0 AND discount_value <= 10000)
    ),
    CONSTRAINT promotions_fixed_chk CHECK (
        discount_type <> 'FIXED' OR discount_value > 0
    ),
    CONSTRAINT promotions_min_spend_chk CHECK (min_spend_minor >= 0),
    CONSTRAINT promotions_span_chk CHECK (
        starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at
    )
);

CREATE TABLE promotions_aud (
    id              UUID,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT NOT NULL,
    name            VARCHAR(120),
    discount_type   VARCHAR(12),
    discount_value  BIGINT,
    min_spend_minor BIGINT,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    active          BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    is_deleted      BOOLEAN,
    PRIMARY KEY (id, rev)
);

-- ------------------------------------------------------------------
-- Vouchers (single-use codes with usage limits) + redemptions
-- ------------------------------------------------------------------
CREATE TABLE vouchers (
    id                        UUID PRIMARY KEY,
    code                      VARCHAR(64)  NOT NULL,
    name                      VARCHAR(120),
    discount_type             VARCHAR(12)  NOT NULL,
    discount_value            BIGINT       NOT NULL,
    min_spend_minor           BIGINT       NOT NULL DEFAULT 0,
    starts_at                 TIMESTAMPTZ,
    ends_at                   TIMESTAMPTZ,
    max_redemptions           BIGINT,
    max_redemptions_per_user  BIGINT,
    active                    BOOLEAN      NOT NULL DEFAULT FALSE,
    version                   BIGINT       NOT NULL,
    created_by                VARCHAR(200),
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_by                VARCHAR(200),
    updated_at                TIMESTAMPTZ  NOT NULL,
    is_deleted                BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT vouchers_code_upper_chk CHECK (code = upper(btrim(code)) AND length(btrim(code)) BETWEEN 4 AND 64),
    CONSTRAINT vouchers_type_chk CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    CONSTRAINT vouchers_percentage_chk CHECK (
        discount_type <> 'PERCENTAGE' OR (discount_value > 0 AND discount_value <= 10000)
    ),
    CONSTRAINT vouchers_fixed_chk CHECK (
        discount_type <> 'FIXED' OR discount_value > 0
    ),
    CONSTRAINT vouchers_min_spend_chk CHECK (min_spend_minor >= 0),
    CONSTRAINT vouchers_limits_chk CHECK (
        (max_redemptions IS NULL OR max_redemptions > 0)
        AND (max_redemptions_per_user IS NULL OR max_redemptions_per_user > 0)
    ),
    CONSTRAINT vouchers_span_chk CHECK (
        starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at
    )
);

CREATE UNIQUE INDEX uq_vouchers_code ON vouchers(code) WHERE is_deleted = FALSE;

CREATE TABLE vouchers_aud (
    id                        UUID,
    rev                       INTEGER NOT NULL,
    revtype                   SMALLINT NOT NULL,
    code                      VARCHAR(64),
    name                      VARCHAR(120),
    discount_type             VARCHAR(12),
    discount_value            BIGINT,
    min_spend_minor           BIGINT,
    starts_at                 TIMESTAMPTZ,
    ends_at                   TIMESTAMPTZ,
    max_redemptions           BIGINT,
    max_redemptions_per_user  BIGINT,
    active                    BOOLEAN,
    version                   BIGINT,
    created_by                VARCHAR(200),
    created_at                TIMESTAMPTZ,
    updated_by                VARCHAR(200),
    updated_at                TIMESTAMPTZ,
    is_deleted                BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE TABLE voucher_redemptions (
    id            UUID PRIMARY KEY,
    voucher_id    UUID NOT NULL REFERENCES vouchers(id),
    order_id      UUID NOT NULL,
    consumer_id   UUID NOT NULL,
    discount_minor BIGINT NOT NULL,
    currency      CHAR(3) NOT NULL,
    redeemed_at   TIMESTAMPTZ NOT NULL,
    version       BIGINT NOT NULL,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT voucher_redemptions_discount_chk CHECK (discount_minor >= 0)
);

-- THE voucher idempotency law: one redemption per (voucher, order) — a
-- replayed placement event or retried command collides here, never
-- double-counts. (order_id has NO FK — orders is another module, V32.)
CREATE UNIQUE INDEX uq_voucher_redemptions_voucher_order
    ON voucher_redemptions(voucher_id, order_id) WHERE is_deleted = FALSE;

CREATE INDEX idx_voucher_redemptions_consumer
    ON voucher_redemptions(voucher_id, consumer_id) WHERE is_deleted = FALSE;

CREATE TABLE voucher_redemptions_aud (
    id             UUID,
    rev            INTEGER NOT NULL,
    revtype        SMALLINT NOT NULL,
    voucher_id     UUID,
    order_id       UUID,
    consumer_id    UUID,
    discount_minor BIGINT,
    currency       CHAR(3),
    redeemed_at    TIMESTAMPTZ,
    version        BIGINT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ,
    is_deleted     BOOLEAN,
    PRIMARY KEY (id, rev)
);

-- ------------------------------------------------------------------
-- Tax rates (explicit jurisdiction policy — one active rate per country)
-- ------------------------------------------------------------------
CREATE TABLE tax_rates (
    id           UUID PRIMARY KEY,
    country_code CHAR(2)     NOT NULL,
    name         VARCHAR(120) NOT NULL,
    rate_bps     INTEGER     NOT NULL,
    tax_mode     VARCHAR(12) NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT FALSE,
    version      BIGINT      NOT NULL,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT tax_rates_country_upper_chk CHECK (country_code = upper(country_code)),
    CONSTRAINT tax_rates_bps_chk CHECK (rate_bps >= 0 AND rate_bps <= 10000),
    CONSTRAINT tax_rates_mode_chk CHECK (tax_mode IN ('INCLUSIVE', 'EXCLUSIVE'))
);

-- one ACTIVE rate per country (partial unique — superseding a rate is a
-- new row + deactivating the old one, never an edit of history)
CREATE UNIQUE INDEX uq_tax_rates_country_active
    ON tax_rates(country_code) WHERE active = TRUE AND is_deleted = FALSE;

CREATE TABLE tax_rates_aud (
    id           UUID,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT NOT NULL,
    country_code CHAR(2),
    name         VARCHAR(120),
    rate_bps     INTEGER,
    tax_mode     VARCHAR(12),
    active       BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    is_deleted   BOOLEAN,
    PRIMARY KEY (id, rev)
);

-- ------------------------------------------------------------------
-- The order's frozen monetary breakdown (orders module's own tables)
-- ------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN subtotal_amount_minor BIGINT,
    ADD COLUMN discount_amount_minor BIGINT,
    ADD COLUMN tax_amount_minor      BIGINT;

ALTER TABLE orders_aud
    ADD COLUMN subtotal_amount_minor BIGINT,
    ADD COLUMN discount_amount_minor BIGINT,
    ADD COLUMN tax_amount_minor      BIGINT;

-- NULLable columns keep every legacy order's truth whole: an order placed
-- before V223 has no recorded breakdown; its total_amount_minor stays the
-- authoritative agreement exactly as frozen at ITS placement.
ALTER TABLE orders
    ADD CONSTRAINT orders_breakdown_chk CHECK (
        subtotal_amount_minor IS NULL
        OR (subtotal_amount_minor >= 0
            AND discount_amount_minor IS NOT NULL
            AND tax_amount_minor IS NOT NULL
            AND subtotal_amount_minor - discount_amount_minor >= 0)
    );

CREATE TABLE order_price_components (
    id             UUID PRIMARY KEY,
    order_id       UUID NOT NULL REFERENCES orders(id),
    component_type VARCHAR(12) NOT NULL,
    reference_id   UUID,
    reference_code VARCHAR(120),
    amount_minor   BIGINT NOT NULL,
    currency       CHAR(3) NOT NULL,
    version        BIGINT NOT NULL,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ NOT NULL,
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT order_price_components_type_chk
        CHECK (component_type IN ('PROMOTION', 'VOUCHER', 'TAX')),
    CONSTRAINT order_price_components_amount_chk CHECK (amount_minor >= 0)
);

CREATE INDEX idx_order_price_components_order
    ON order_price_components(order_id) WHERE is_deleted = FALSE;

CREATE TABLE order_price_components_aud (
    id             UUID,
    rev            INTEGER NOT NULL,
    revtype        SMALLINT NOT NULL,
    order_id       UUID,
    component_type VARCHAR(12),
    reference_id   UUID,
    reference_code VARCHAR(120),
    amount_minor   BIGINT,
    currency       CHAR(3),
    version        BIGINT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ,
    is_deleted     BOOLEAN,
    PRIMARY KEY (id, rev)
);
