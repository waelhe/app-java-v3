-- The 2026-11-06 candidate CI round: V300 added orders.cancel_author but
-- missed the Envers audit mirror — every audited order write (placement's
-- INSERT included) failed on
-- "column \"cancel_author\" of relation \"orders_aud\" does not exist"
-- (SQLState 42703; the full order-journey integration matrix answered
-- 500 INT-001). The byte-freeze law (AGENTS.md / MigrationChecksumGuardTest)
-- keeps the registered V300 byte-identical, so the mirror rides its own
-- version number — the same shape V224 used when the audited shipping
-- columns joined orders_aud, and V235's fulfillment_method widening.
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS cancel_author VARCHAR(20);
