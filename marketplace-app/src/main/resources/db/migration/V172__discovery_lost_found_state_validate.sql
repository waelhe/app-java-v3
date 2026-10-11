-- Validate V171's lost-and-found state vocabulary in its own migration.
-- This keeps the constraint-validation scan out of the transaction that
-- acquires the stronger DDL lock while adding the CHECK constraint
-- (the V167 pattern verbatim: alone, the VALIDATE's own lock is SHARE
-- UPDATE EXCLUSIVE — the shared production database keeps serving
-- traffic).
ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_lost_found_state;
