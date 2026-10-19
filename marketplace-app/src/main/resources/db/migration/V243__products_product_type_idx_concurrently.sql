-- The products' live product-type partial index, built CONCURRENTLY in its
-- own non-transactional migration — the house's populated-table discipline
-- (V211 verbatim): products carries the catalog since the early schema, and
-- a plain CREATE INDEX inside V238's transaction would block ordinary
-- writes for the whole build (including the still-live previous instance's
-- catalog writes during the blue/green deploy window the Railway channel
-- serves). PostgreSQL's own documented remedy is CREATE INDEX CONCURRENTLY;
-- the price (a slower build, no transactional wrap) is the one the house's
-- split carries through the .sql.conf (executeInTransaction=false)
-- discipline.
CREATE INDEX CONCURRENTLY idx_products_product_type_live
    ON products (product_type_id)
    WHERE product_type_id IS NOT NULL AND is_deleted = FALSE;
