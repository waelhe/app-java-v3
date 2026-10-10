-- A-11 (official-compliance plan §6, wave C — C.1: the store's order flow,
-- cart → order → fulfillment as an event-driven state machine): the four
-- tables the machine owns.
--
-- WHY carts carries a partial unique (consumer_id) WHERE status = 'ACTIVE'
-- AND is_deleted = FALSE: one live draft per buyer — the DB-level
-- single-flight rule (the V64/V73/V83/V91/V100/V112 partial-unique tool).
-- OrdersService.getOrCreateActiveCart is idempotent UNDER the index: a
-- concurrent double-create loses exactly the way a duplicate direct
-- conversation loses to V67 (23505 → the house 409 translation).
--
-- WHY the status CHECKs (the D-N7 discipline): every enumerated column
-- carries its DB-level membership guard — the Java enum (CartStatus /
-- OrderStatus) is the application's single source of truth, this
-- constraint is the store's. Fresh tables, zero rows: the membership check
-- validates inline (the V78/V99/V100/V112 fresh-table scale-class
-- decision — NOT VALID + a separate VALIDATE migration is the
-- populated-table pattern only).
--
-- WHY product_id carries NO foreign key (the measured, documented
-- boundary): the store's product root (compliance plan C.7/M1, unit A-17)
-- does not exist yet, and the Modulith-conformant shape for cross-module
-- references is the opaque id (the module never reads catalog's tables —
-- the reviews precedent keeps booking_id untyped exactly the same way).
-- When M1 lands, the placement flow gains the authoritative price
-- resolution against the real product rows; the amount-snapshot columns
-- below are the buyer-agreement record the placement freezes, and the M1
-- integration only replaces the SOURCE of the amounts, never their path.
--
-- WHY consumer_id REFERENCES users(id): the A1 convention — identity is
-- the anchor module every cross-module reference keys on (the measured V6
-- reviews precedent: provider_id uuid not null references users(id)).
--
-- BaseEntity house columns (version/created_by/created_at/updated_by/
-- updated_at/is_deleted — the V100/V112 column set verbatim): @Version
-- guards the machine's transitions (two concurrent confirms of the same
-- order — the second loses the optimistic lock), and @SoftDelete means
-- every unique index below must stay live-only-aware through its WHERE
-- is_deleted = FALSE predicate (the V67 lesson).
--
-- Track A's Flyway range V110-V149 (parallel execution plan §5.3; V110/
-- V111 consumed by A-03, V112 by A-04) — this is the range's third
-- consumption.

-- The buyer's single live draft.
CREATE TABLE carts (
    id            UUID PRIMARY KEY,
    consumer_id   UUID NOT NULL,
    status        VARCHAR(20) NOT NULL,
    checked_out_at TIMESTAMPTZ,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_carts_users
        FOREIGN KEY (consumer_id) REFERENCES users (id),
    CONSTRAINT carts_status_check
        CHECK (status IN ('ACTIVE', 'CHECKED_OUT'))
);

-- One live cart per buyer — the partial-unique single-flight rule.
CREATE UNIQUE INDEX ix_carts_consumer_active
    ON carts (consumer_id)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;

-- The cart's lines.
CREATE TABLE cart_items (
    id               UUID PRIMARY KEY,
    cart_id          UUID NOT NULL,
    product_id       UUID NOT NULL,
    quantity         INTEGER NOT NULL,
    unit_amount_minor BIGINT NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cart_items_carts
        FOREIGN KEY (cart_id) REFERENCES carts (id),
    CONSTRAINT cart_items_quantity_check
        CHECK (quantity > 0),
    CONSTRAINT cart_items_amount_check
        CHECK (unit_amount_minor >= 0)
);

-- One line per product per cart (a duplicate add collapses into the
-- quantity bump — the service's union semantics, guarded here).
CREATE UNIQUE INDEX uq_cart_items_cart_product
    ON cart_items (cart_id, product_id)
    WHERE is_deleted = FALSE;

-- The machine's aggregate.
CREATE TABLE orders (
    id                UUID PRIMARY KEY,
    consumer_id       UUID NOT NULL,
    status            VARCHAR(20) NOT NULL,
    placed_at         TIMESTAMPTZ NOT NULL,
    confirmed_at      TIMESTAMPTZ,
    fulfilled_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    cancel_reason     TEXT,
    total_amount_minor BIGINT NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    is_deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    version           BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_orders_users
        FOREIGN KEY (consumer_id) REFERENCES users (id),
    CONSTRAINT orders_status_check
        CHECK (status IN ('PLACED', 'CONFIRMED', 'FULFILLED', 'CANCELLED')),
    CONSTRAINT orders_amount_check
        CHECK (total_amount_minor >= 0)
);

-- The buyer's own history page (newest-first, the D-N5 complete key).
CREATE INDEX idx_orders_consumer_placed
    ON orders (consumer_id, placed_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The order's frozen lines.
CREATE TABLE order_items (
    id                UUID PRIMARY KEY,
    order_id          UUID NOT NULL,
    product_id        UUID NOT NULL,
    quantity          INTEGER NOT NULL,
    unit_amount_minor BIGINT NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    is_deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    version           BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_items_orders
        FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT order_items_quantity_check
        CHECK (quantity > 0),
    CONSTRAINT order_items_amount_check
        CHECK (unit_amount_minor >= 0)
);

-- One line per product per order (the frozen snapshot's own shape).
CREATE UNIQUE INDEX uq_order_items_order_product
    ON order_items (order_id, product_id)
    WHERE is_deleted = FALSE;

-- The placement join reads every cart line in one hop.
CREATE INDEX idx_cart_items_cart
    ON cart_items (cart_id)
    WHERE is_deleted = FALSE;

-- The order's item listing reads in one hop.
CREATE INDEX idx_order_items_order
    ON order_items (order_id)
    WHERE is_deleted = FALSE;

-- Envers audit mirrors (the V24 house pattern): the machine's every
-- transition is a revision — the audit trail the state machine's own
-- clock columns and the service guards leave behind.
CREATE TABLE carts_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    consumer_id UUID,
    status VARCHAR(20),
    checked_out_at TIMESTAMPTZ,
    version BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE TABLE cart_items_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    cart_id UUID,
    product_id UUID,
    quantity INTEGER,
    unit_amount_minor BIGINT,
    currency VARCHAR(3),
    version BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE TABLE orders_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    consumer_id UUID,
    status VARCHAR(20),
    placed_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    fulfilled_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancel_reason TEXT,
    total_amount_minor BIGINT,
    currency VARCHAR(3),
    version BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE TABLE order_items_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    order_id UUID,
    product_id UUID,
    quantity INTEGER,
    unit_amount_minor BIGINT,
    currency VARCHAR(3),
    version BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN,
    PRIMARY KEY (id, rev)
);
