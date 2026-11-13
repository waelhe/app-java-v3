-- The 2026-11-05 #811 round-3 review (Major · Security & Privacy): the
-- cancel reason's AUTHORSHIP. A seller cancellation (cancelForSeller)
-- and the administrative cancellations write their reason on a row whose
-- consumer_id is the BUYER — the erasure purge keyed on consumer_id used
-- to erase the SELLER'S own words under the buyer's erasure right (and
-- the audit copies with them). The author column lets the purge keep the
-- seller/admin reason (their own authored text, the shared commercial
-- record) and purge only the buyer's own words. Legacy rows carry NULL:
-- the pre-attribution population keeps the standing purge behavior (the
-- buyer-facing self-cancel was the platform's dominant path).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancel_author VARCHAR(20);

-- The closed vocabulary the service writes (CONSUMER/SELLER/ADMIN) —
-- free-form values are a defect, not a contract.
ALTER TABLE orders ADD CONSTRAINT chk_orders_cancel_author
    CHECK (cancel_author IS NULL OR cancel_author IN ('CONSUMER', 'SELLER', 'ADMIN'));
