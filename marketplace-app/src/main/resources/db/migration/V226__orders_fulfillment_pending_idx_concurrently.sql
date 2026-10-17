-- V226 — the pending-fulfillment index, built CONCURRENTLY in its own
-- non-transactional migration (the house's populated-table discipline:
-- V51/V67/V80/V81/V105/V168/V169/V173/V186/V211/V212 verbatim — a plain
-- CREATE INDEX inside V224's transaction would block ordinary writes on
-- the orders table for the whole build, including the still-live
-- previous instance's writes during the deploy window). The partial
-- predicate matches the operational read: dispatch and the fulfillment
-- gate scan an order's pending platform-delivery legs.
CREATE INDEX CONCURRENTLY idx_orders_fulfillment_pending
    ON orders(fulfillment_method, status)
    WHERE fulfillment_method = 'PLATFORM_DELIVERY' AND is_deleted = FALSE;
