-- V235 — widen orders.fulfillment_method to fit its own closed vocabulary.
--
-- V224 declared the column VARCHAR(16) while its own CHECK constraint
-- (orders_fulfillment_method_chk) admits the value 'PLATFORM_DELIVERY'
-- (17 characters) — the ADR-0013 D4 launch mode. Every real checkout that
-- froze the platform's own delivery failed the INSERT with
-- «value too long for type character varying(16)» (a 500 INT-001 — first
-- caught end-to-end by the J25 journey test, PR #654).
--
-- Widening a varchar length limit is a metadata-only change on PostgreSQL
-- (no table rewrite, no index rebuild — the column carries no index);
-- the CHECK constraints are untouched (the vocabulary itself is unchanged).
ALTER TABLE orders
    ALTER COLUMN fulfillment_method TYPE VARCHAR(32);

ALTER TABLE orders_aud
    ALTER COLUMN fulfillment_method TYPE VARCHAR(32);
