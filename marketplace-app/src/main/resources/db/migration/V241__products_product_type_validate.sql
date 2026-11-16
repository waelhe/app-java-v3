-- Validate V238's product-type foreign key in its own migration: the
-- validation scan stays out of the transaction that acquires the DDL lock
-- (the house's V111/V115/V152/V159/V165/V167 discipline verbatim). Cheap
-- today — every product_type_id is NULL until the typed catalog writes.
ALTER TABLE products VALIDATE CONSTRAINT fk_products_product_type;
