-- Validate V174's event status vocabulary in its own migration.
-- This keeps the constraint-validation scan out of the transaction that
-- acquires the stronger DDL lock while adding the CHECK constraint
-- (the V167 pattern verbatim: alone, the VALIDATE's own lock is SHARE
-- UPDATE EXCLUSIVE — the shared production database keeps serving
-- traffic).
ALTER TABLE neighborhood_events VALIDATE CONSTRAINT chk_neighborhood_events_status;
