-- V228 — the legs-by-order operational index, built CONCURRENTLY in its
-- own non-transactional migration (the populated-table discipline — the
-- V212 precedent verbatim): dispatch queues and the multi-leg completion
-- gate count an order's active legs (remainingActiveLegs); the partial
-- predicate covers exactly the order-backed, undeleted rows.
CREATE INDEX CONCURRENTLY idx_delivery_requests_order_active
    ON delivery_requests(order_id, status)
    WHERE order_id IS NOT NULL AND is_deleted = FALSE;
