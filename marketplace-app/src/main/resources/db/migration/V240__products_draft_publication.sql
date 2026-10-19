-- C1: typed goods are created as DRAFT so configuration/editing can be
-- completed before an explicit publish command. Legacy untyped products keep
-- their historical ACTIVE-on-register behavior; existing persisted rows are
-- unchanged. Publication only widens the existing closed state check.
-- The split (the review round on the C1 wave): the widened CHECK lands
-- NOT VALID here and validates in V242 — the ACCESS EXCLUSIVE lock the
-- DROP/ADD pair takes must never be held through the validation scan.
ALTER TABLE products
    DROP CONSTRAINT products_status_chk;

ALTER TABLE products
    ADD CONSTRAINT products_status_chk
        CHECK (product_status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')) NOT VALID;
