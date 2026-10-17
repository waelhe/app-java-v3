-- V224 — the checkout fulfillment selection (the post-#604 integration the
-- platform-wide payments-and-delivery architecture §3.2 Mode B mandates):
-- «During checkout, resolve the available fulfillment options [...]; Freeze
-- the agreed delivery method, amount/currency, address snapshot, promised
-- window, and relevant quote/rule version alongside the order-time
-- commercial agreement».
--
-- Two sides:
--   * ORDERS: the frozen selection — the method (PICKUP or the platform's
--     own delivery), the shipping amount (already inside the order's total
--     and its ONE payment intent — no second charge), and the destination
--     snapshot (address + coordinates).
--   * DELIVERY: checkout-created legs carry NO quote (the fee was frozen at
--     placement, not quoted post-confirmation) — quote_id becomes nullable
--     with the uniqueness kept WHERE a quote exists; the leg starts at
--     READY_FOR_DISPATCH (the payment already rode the order's intent).
--
-- Cross-module FK discipline: orders.ship_to_* are order-local columns;
-- delivery_requests.order_id keeps its V218 FK (the #604 measured stance).

-- ------------------------------------------------------------------
-- The orders' frozen fulfillment selection
-- ------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN fulfillment_method   VARCHAR(16),
    ADD COLUMN shipping_amount_minor BIGINT,
    ADD COLUMN ship_to_address      VARCHAR(250),
    ADD COLUMN ship_to_latitude     DOUBLE PRECISION,
    ADD COLUMN ship_to_longitude    DOUBLE PRECISION;

ALTER TABLE orders_aud
    ADD COLUMN fulfillment_method   VARCHAR(16),
    ADD COLUMN shipping_amount_minor BIGINT,
    ADD COLUMN ship_to_address      VARCHAR(250),
    ADD COLUMN ship_to_latitude     DOUBLE PRECISION,
    ADD COLUMN ship_to_longitude    DOUBLE PRECISION;

-- NULL = the pre-V224 agreement (no selection recorded — legacy orders keep
-- their total as the whole agreement, exactly as frozen at THEIR placement).
ALTER TABLE orders
    ADD CONSTRAINT orders_fulfillment_method_chk CHECK (
        fulfillment_method IS NULL OR fulfillment_method IN ('PICKUP', 'PLATFORM_DELIVERY')
    );

-- The selection's shape law: a destination exists exactly when the platform
-- delivers; PICKUP and legacy orders carry none; a platform delivery always
-- carries a positive frozen shipping amount (a free delivery is a promotion,
-- not a fulfillment method).
ALTER TABLE orders
    ADD CONSTRAINT orders_fulfillment_shape_chk CHECK (
        (fulfillment_method IS NULL
            AND ship_to_address IS NULL AND ship_to_latitude IS NULL AND ship_to_longitude IS NULL
            AND (shipping_amount_minor IS NULL OR shipping_amount_minor = 0))
        OR (fulfillment_method = 'PICKUP'
            AND ship_to_address IS NULL AND ship_to_latitude IS NULL AND ship_to_longitude IS NULL
            AND (shipping_amount_minor IS NULL OR shipping_amount_minor = 0))
        OR (fulfillment_method = 'PLATFORM_DELIVERY'
            AND ship_to_address IS NOT NULL AND length(btrim(ship_to_address)) > 0
            AND ship_to_latitude IS NOT NULL AND ship_to_latitude BETWEEN -90 AND 90
            AND ship_to_longitude IS NOT NULL AND ship_to_longitude BETWEEN -180 AND 180
            AND shipping_amount_minor IS NOT NULL AND shipping_amount_minor > 0)
    );

CREATE INDEX idx_orders_fulfillment_pending
    ON orders(fulfillment_method, status)
    WHERE fulfillment_method = 'PLATFORM_DELIVERY' AND is_deleted = FALSE;

-- ------------------------------------------------------------------
-- The delivery legs without a quote (the checkout-created legs)
-- ------------------------------------------------------------------
ALTER TABLE delivery_requests ALTER COLUMN quote_id DROP NOT NULL;

-- the inline UNIQUE of V215 becomes a partial unique: a leg either quotes
-- (the #604 direct/manual path — one request per quote) or was created from
-- the order's frozen selection (quote_id NULL, many legs per order allowed
-- — the multi-leg model: one order, several packages).
ALTER TABLE delivery_requests DROP CONSTRAINT delivery_requests_quote_id_key;
CREATE UNIQUE INDEX uq_delivery_requests_quote
    ON delivery_requests(quote_id) WHERE quote_id IS NOT NULL;

-- a leg always traces to ITS origin: a quote (direct service) or an order
-- (checkout selection / manual order delivery). A quote-less, order-less
-- leg cannot exist.
ALTER TABLE delivery_requests
    ADD CONSTRAINT delivery_requests_origin_chk CHECK (
        quote_id IS NOT NULL OR order_id IS NOT NULL
    );

-- the legs-by-order operational read (dispatch queues and the completion
-- gate count an order's active legs)
CREATE INDEX idx_delivery_requests_order_active
    ON delivery_requests(order_id, status)
    WHERE order_id IS NOT NULL AND is_deleted = FALSE;
