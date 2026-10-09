-- Validate V166's widened category vocabulary in its own migration.
-- Flyway executes each versioned migration transactionally; keeping this
-- scan separate prevents validation from holding V166's DDL lock.
ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_category;
