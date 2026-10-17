-- V230 — the return/exchange (RMA) lifecycle, modeled SEPARATELY from
-- payment refunds (the Saleor fit-gap study §7-Phase-3 item 4: «Model
-- returns/exchanges separately from payment refunds and disputes»): a
-- payment refund is a money movement; a RETURN is goods coming back —
-- its own guarded lifecycle (REQUESTED → APPROVED → RECEIVED → COMPLETED,
-- with REJECTED/CANCELLED from the open states), its own lines (WHICH
-- goods, how many), and its completion DRIVES the money (the frozen
-- partial refund through the ONE refund contract) and the shelves (the
-- restock) — never the other way around.
--
-- Ownership: marketplace-orders owns the lifecycle (the order domain's
-- own post-fulfillment machine); the money executes through
-- marketplace-payments' OrderRefundPort; the shelves restock through
-- marketplace-catalog's ProductStockPort.restock — each fact stays with
-- its owner (the V32 discipline).

CREATE TABLE order_returns (
    id                    UUID PRIMARY KEY,
    order_id              UUID NOT NULL REFERENCES orders(id),
    consumer_id           UUID NOT NULL,
    return_type           VARCHAR(10) NOT NULL,
    status                VARCHAR(12) NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    resolution_note       VARCHAR(500),
    -- the FROZEN refund share, computed at APPROVAL from the order's own
    -- frozen breakdown (the lines' share of the discounted goods value,
    -- plus the exclusive-tax share when the jurisdiction adds it) — the
    -- completion executes exactly this number, never a recomputation
    refund_amount_minor   BIGINT,
    currency              CHAR(3) NOT NULL,
    replacement_order_id  UUID,
    requested_at          TIMESTAMPTZ NOT NULL,
    approved_at           TIMESTAMPTZ,
    received_at           TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    version               BIGINT NOT NULL,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ NOT NULL,
    is_deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT order_returns_type_chk CHECK (return_type IN ('RETURN', 'EXCHANGE')),
    CONSTRAINT order_returns_status_chk CHECK (status IN (
        'REQUESTED', 'APPROVED', 'REJECTED', 'RECEIVED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT order_returns_refund_shape_chk CHECK (
        (status IN ('REQUESTED', 'REJECTED', 'CANCELLED') AND refund_amount_minor IS NULL)
        OR (status IN ('APPROVED', 'RECEIVED', 'COMPLETED') AND refund_amount_minor > 0)
    ),
    CONSTRAINT order_returns_exchange_shape_chk CHECK (
        return_type <> 'EXCHANGE' OR status <> 'COMPLETED' OR replacement_order_id IS NOT NULL
    ),
    CONSTRAINT order_returns_clocks_chk CHECK (
        (approved_at IS NULL OR approved_at >= requested_at)
        AND (received_at IS NULL OR received_at >= approved_at)
        AND (completed_at IS NULL OR completed_at >= received_at)
    )
);

CREATE TABLE order_returns_aud (
    id                    UUID,
    rev                   INTEGER NOT NULL,
    revtype               SMALLINT NOT NULL,
    order_id              UUID,
    consumer_id           UUID,
    return_type           VARCHAR(10),
    status                VARCHAR(12),
    reason                VARCHAR(500),
    resolution_note       VARCHAR(500),
    refund_amount_minor   BIGINT,
    currency              CHAR(3),
    replacement_order_id  UUID,
    requested_at          TIMESTAMPTZ,
    approved_at           TIMESTAMPTZ,
    received_at           TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    version               BIGINT,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ,
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE INDEX idx_order_returns_order
    ON order_returns(order_id, created_at DESC) WHERE is_deleted = FALSE;

CREATE INDEX idx_order_returns_consumer_status
    ON order_returns(consumer_id, status) WHERE is_deleted = FALSE;

CREATE TABLE order_return_lines (
    id               UUID PRIMARY KEY,
    order_return_id  UUID NOT NULL REFERENCES order_returns(id),
    order_item_id    UUID NOT NULL REFERENCES order_items(id),
    product_id       UUID NOT NULL,
    variant_id       UUID,
    quantity         INTEGER NOT NULL,
    version          BIGINT NOT NULL,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT order_return_lines_qty_chk CHECK (quantity > 0),
    -- one line per order item per return (a second request for the same
    -- item is a second RETURN, not a quantity merge)
    CONSTRAINT uq_order_return_lines_item UNIQUE (order_return_id, order_item_id)
);

CREATE TABLE order_return_lines_aud (
    id               UUID,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT NOT NULL,
    order_return_id  UUID,
    order_item_id    UUID,
    product_id       UUID,
    variant_id       UUID,
    quantity         INTEGER,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    is_deleted       BOOLEAN,
    PRIMARY KEY (id, rev)
);

CREATE INDEX idx_order_return_lines_return
    ON order_return_lines(order_return_id) WHERE is_deleted = FALSE;

CREATE INDEX idx_order_return_lines_item
    ON order_return_lines(order_item_id) WHERE is_deleted = FALSE;
