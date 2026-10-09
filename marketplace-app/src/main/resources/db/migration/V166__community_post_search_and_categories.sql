-- Code-first extension of the neighborhood feed:
-- (1) QUESTION and REQUEST become explicit post-purpose categories.
-- (2) PostgreSQL remains the first search engine: Arabic FTS plus the
--     existing pg_trgm extension, matching the established V9/V34/V106
--     listing-search pattern without adding an external search service.
--
-- The CHECK is widened in NOT VALID form and validated separately in
-- V167 so the validation scan does not retain the ALTER's stronger lock.
ALTER TABLE neighborhood_posts DROP CONSTRAINT IF EXISTS chk_neighborhood_posts_category;

ALTER TABLE neighborhood_posts
    ADD CONSTRAINT chk_neighborhood_posts_category
    CHECK (category IN (
        'GENERAL', 'CLASSIFIED', 'LOST_FOUND', 'RECOMMENDATION', 'QUESTION', 'REQUEST'
    )) NOT VALID;

CREATE INDEX idx_neighborhood_posts_search_fts
    ON neighborhood_posts USING gin (
        to_tsvector('arabic', coalesce(title, '') || ' ' || coalesce(body, ''))
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';

CREATE INDEX idx_neighborhood_posts_search_trgm
    ON neighborhood_posts USING gin (
        (coalesce(title, '') || ' ' || coalesce(body, '')) gin_trgm_ops
    )
    WHERE is_deleted = FALSE AND status = 'VISIBLE';
