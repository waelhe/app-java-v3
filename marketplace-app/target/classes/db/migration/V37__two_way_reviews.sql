-- L21 (feature-expansion roadmap §5, Week 2): two-way reviews.
-- (a) The provider reply rides the review row itself — one review carries at
--     most one reply (unique by construction: the column pair is null until
--     the first reply and the entity method rejects a second).
-- (b) The stored rating average on the provider profile — recomputed from
--     the reviews table by the review events (ReviewCreatedEvent /
--     ReviewUpdatedEvent) and exposed on the provider read.
-- The Envers audit mirrors (reviews_aud / provider_profiles_aud, V24) gain
-- the same columns so @Audited snapshots keep writing.

ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS reply TEXT,
    ADD COLUMN IF NOT EXISTS replied_at TIMESTAMPTZ;

ALTER TABLE provider_profiles
    ADD COLUMN IF NOT EXISTS rating_average DOUBLE PRECISION;

ALTER TABLE reviews_aud
    ADD COLUMN IF NOT EXISTS reply TEXT,
    ADD COLUMN IF NOT EXISTS replied_at TIMESTAMPTZ;

ALTER TABLE provider_profiles_aud
    ADD COLUMN IF NOT EXISTS rating_average DOUBLE PRECISION;
