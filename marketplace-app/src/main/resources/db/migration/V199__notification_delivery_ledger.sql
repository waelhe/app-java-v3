-- Phase 7 (execution plan §10 / §8.1 — notification routing): the
-- per-channel delivery ledger — the channel dimension of the §8.1
-- idempotency identity ("event id + recipient + channel").
--
-- The notifications table's own source_event_id column (V180) ledgers
-- the IN-APP (inbox) delivery: one row per (recipient, source event)
-- EVER, guarded by the uq_notifications_source_event_once partial
-- unique index. EMAIL, WS and PUSH have no inbox row to carry that key —
-- a retried publication (the Event Publication Registry's resubmission
-- of an incomplete entry) would repeat the standing L22 paths' known
-- at-least-once behavior: the email re-sends, the WebSocket re-broadcasts
-- (the SavedSearchMatchedEvent javadoc's declared debt D-E10, the
-- realestate-systems-plan §8 record). THIS table is the standing
-- pattern's channel dimension: one row per (source event, recipient,
-- channel) EVER DELIVERED, written with the V54 saved_search_matches
-- native INSERT ... ON CONFLICT DO NOTHING (the skip is a returned 0,
-- never a transaction-aborting 23505 — a re-delivered publication takes
-- the skip branch and the retry stays exactly-once per channel).
--
-- OPERATIONAL LEDGER, NOT A DOMAIN AGGREGATE (the event_publication /
-- saved_search_matches precedent verbatim): rows are born complete at
-- the delivery moment and never mutated, so there is no mutable state
-- for an Envers mirror to track (the row IS the audit trail) and no
-- module entity (the writes ride JdbcTemplate inside the delivery
-- transaction). channel carries the OUTBOUND channels only — the inbox
-- leg keeps its standing V180 ledger and is deliberately not duplicated
-- here.
--
-- The (recipient, delivered_at DESC) index is §8.1's "قياس التسليم"
-- seat: the delivery history is a plain ordered read over this ledger.
--
-- Numbering: V199 — the Phase 7 wave's reserved range (V198-V205).

CREATE TABLE notification_deliveries (
    id           UUID PRIMARY KEY,
    event_id     UUID NOT NULL,
    recipient_id UUID NOT NULL,
    channel      VARCHAR(20) NOT NULL
                 CONSTRAINT notification_deliveries_channel_check
                 CHECK (channel IN ('EMAIL', 'WS', 'PUSH')) NOT VALID,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE notification_deliveries VALIDATE CONSTRAINT notification_deliveries_channel_check;

-- One delivery per (source event, recipient, channel) EVER — the §8.1
-- idempotency identity, the V54 skip-key shape verbatim.
CREATE UNIQUE INDEX uq_notification_deliveries_once
    ON notification_deliveries (event_id, recipient_id, channel);

-- The delivery-measurement read (§8.1 "قياس التسليم"): one recipient's
-- ledger, freshest first (the D-N5 complete ordering key shape).
CREATE INDEX idx_notification_deliveries_recipient_recent
    ON notification_deliveries (recipient_id, delivered_at DESC);
