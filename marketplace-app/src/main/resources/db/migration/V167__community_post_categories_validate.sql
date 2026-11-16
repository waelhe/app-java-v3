-- Validate V166's widened category vocabulary in its own migration.
-- This keeps the constraint-validation scan out of the transaction that
-- acquires the stronger DDL lock while replacing the CHECK constraint.
ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_category;
