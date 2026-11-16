-- Widen the neighborhood-post purpose vocabulary without editing V61/V68.
-- Use the established NOT VALID -> separate VALIDATE pattern (V68/V69).
-- Search indexes are built CONCURRENTLY in V168, outside this transaction,
-- so adding the capability does not unnecessarily block feed writes.
ALTER TABLE neighborhood_posts DROP CONSTRAINT IF EXISTS chk_neighborhood_posts_category;

ALTER TABLE neighborhood_posts
    ADD CONSTRAINT chk_neighborhood_posts_category
    CHECK (category IN (
        'GENERAL', 'CLASSIFIED', 'LOST_FOUND', 'RECOMMENDATION', 'QUESTION', 'REQUEST'
    )) NOT VALID;
