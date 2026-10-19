-- C1: link a goods Product to its reusable ProductType without rewriting
-- legacy rows. The nullable field preserves products already created before
-- product types existed and retains the accepted zero-variant purchase path.
-- V235 belongs to open PR #654; V236/V237 are this C1 branch's type and
-- attribute roots. V238 must only be merged after the migration sequence is
-- reconciled with #654 and the checksum guard has passed.
ALTER TABLE products
    ADD COLUMN product_type_id UUID;

ALTER TABLE products
    ADD CONSTRAINT fk_products_product_type
        FOREIGN KEY (product_type_id) REFERENCES product_types (id) NOT VALID;

ALTER TABLE products
    VALIDATE CONSTRAINT fk_products_product_type;

ALTER TABLE products_aud
    ADD COLUMN product_type_id UUID;

CREATE INDEX idx_products_product_type_live
    ON products (product_type_id)
    WHERE product_type_id IS NOT NULL AND is_deleted = FALSE;
