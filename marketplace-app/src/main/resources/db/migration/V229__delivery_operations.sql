-- V229 — the delivery OPERATIONS layer (the platform-wide payments and
-- delivery architecture §7-Phase-4: «rate/service-area/capacity controls,
-- dispatch queues, courier management, exception handling, claims/returns,
-- carrier adapters and settlement reconciliation» — this slice lands the
-- four measurable pieces: the courier lifecycle, the carrier column, and
-- the operational reads ride code, not schema, where possible).
--
--   * delivery_couriers.status — the courier's OPERATIONAL lifecycle
--     (ACTIVE/SUSPENDED): a suspended courier disappears from dispatch
--     eligibility (the assignment gate reads it); availability stays the
--     courier's own live signal, status is the operator's verdict.
--   * delivery_requests.carrier — WHICH carrier executes each leg: the
--     platform's own fleet ('PLATFORM', the launch mode — ADR-0013 D4)
--     or an external carrier's code (arriving behind the CarrierChannel
--     adapter boundary, never as provider-specific API leaks into the
--     core contracts).

ALTER TABLE delivery_couriers
    ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE delivery_couriers
    ADD CONSTRAINT delivery_couriers_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED'));

ALTER TABLE delivery_couriers_aud
    ADD COLUMN status VARCHAR(12);

ALTER TABLE delivery_requests
    ADD COLUMN carrier VARCHAR(40) NOT NULL DEFAULT 'PLATFORM';

ALTER TABLE delivery_requests_aud
    ADD COLUMN carrier VARCHAR(40);

-- the operational queue read: the dispatcher's board groups by the
-- non-terminal states, newest legs first inside each
CREATE INDEX idx_delivery_requests_queue
    ON delivery_requests(status, created_at DESC)
    WHERE is_deleted = FALSE;
