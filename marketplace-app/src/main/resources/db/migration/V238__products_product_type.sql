-- C1: link a goods Product to its reusable ProductType without rewriting
-- legacy rows. The nullable field preserves products already created before
-- product types existed and retains the accepted zero-variant purchase path.
-- V235 landed in main with #654; V236/V237 are this C1 branch's type and
-- attribute roots; the branch advances over that main before merging.
-- The split (the review round on the C1 wave): the FK lands NOT VALID here
-- and validates in V241, and the live partial index builds CONCURRENTLY in
-- V243 — the house's populated-table discipline (V211/V167 verbatim: no
-- lock-heavy step shares the DDL transaction).
ALTER TABLE products
    ADD COLUMN product_type_id UUID;

ALTER TABLE products
    ADD CONSTRAINT fk_products_product_type
        FOREIGN KEY (product_type_id) REFERENCES product_types (id) NOT VALID;

ALTER TABLE products_aud
    ADD COLUMN product_type_id UUID;
