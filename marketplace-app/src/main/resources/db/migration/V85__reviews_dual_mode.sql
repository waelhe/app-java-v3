-- W1 (yelp-level plan §4.2 — the schema delta "V72 بالحرف", §4.5's moderation
-- state column, §4.4's general rating pair, and the daily-cap seed).
--
-- The house rules this file obeys:
--   * CHECKs in the V44/V68 locking shape: NOT VALID (metadata-only, enforced
--     for every NEW row immediately) — the DROP/ADD statements of THIS
--     transaction hold an ACCESS EXCLUSIVE on reviews, so the VALIDATE steps
--     ride their OWN migration (V74 — the V66/V69 measured lesson).
--   * The Envers mirrors gain every new column (the V33 lesson: a base-table
--     column without its _aud twin breaks audit INSERTs silently). Audit
--     columns stay NULLable by convention; defaults only on the base table.
--   * Zero-cost on live data: every new column carries a DEFAULT equal to the
--     existing rows' semantics (origin='BOOKING', moderation='PUBLISHED') —
--     no backfill statement, no existing reader changes behavior.
--
-- (1) G1 — the booking requirement is lifted off the column: the booking
-- stays the verified path's foreign key and reference, but the organic path
-- stores NULL here (the cross-column check below pins the pairing).
ALTER TABLE reviews ALTER COLUMN booking_id DROP NOT NULL;

-- (2) G2 — the origin column. The plan's explicit design: plain VARCHAR with
-- the DB check as the vocabulary (NOT a Java enum reference) — a future third
-- value widens by migration, not by rebuilding a type. 'BOOKING' is the whole
-- existing table.
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS origin VARCHAR(20) NOT NULL DEFAULT 'BOOKING';
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_origin_kind
    CHECK (origin IN ('BOOKING', 'ORGANIC')) NOT VALID;

-- (3) The origin<->booking invariant (the plan's own name): a booking exists
-- if and only if the origin is BOOKING. The database-level guard protects the
-- "verified" badge and both aggregates from any future write path or data
-- repair. NOT VALID now; validated in V74.
ALTER TABLE reviews
    ADD CONSTRAINT ck_review_origin_booking
    CHECK ((origin = 'BOOKING' AND booking_id IS NOT NULL)
        OR (origin = 'ORGANIC' AND booking_id IS NULL)) NOT VALID;

-- (4) The organic review's optional listing target — the plan's FK, same id
-- space and ownership convention as every listing reference.
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS listing_id UUID REFERENCES provider_listings(id);

-- (5) §4.5 — the moderation state (the "soft status column"): PUBLISHED is
-- every existing row and every booking-origin review (zero visible change);
-- PENDING_REVIEW is the first-organic-reviews queue; HIDDEN_BY_MODERATOR is
-- the moderated-away terminal state (the PostStatus vocabulary).
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_moderation_status
    CHECK (moderation_status IN ('PUBLISHED', 'PENDING_REVIEW', 'HIDDEN_BY_MODERATOR')) NOT VALID;

-- (6) The verified-path uniqueness swap (the plan's §4.2 row, the V45 shape):
-- the live index is uq_reviews_booking_direction_active; re-created with the
-- origin='BOOKING' predicate so it governs the verified path alone and the
-- organic rows (NULL booking) are outside it. Atomic index swap in this
-- transaction; (booking_id, direction) uniqueness holds exactly where it
-- held — lossless.
DROP INDEX IF EXISTS uq_reviews_booking_direction_active;
CREATE UNIQUE INDEX uq_reviews_booking_direction_active
    ON reviews (booking_id, direction) WHERE origin = 'BOOKING' AND is_deleted = false;

-- (7) G10 — the organic 1x1 uniqueness: one user x one provider, forever
-- (the partial-index pattern that guards the verified path today). The
-- service's explicit 409 reads existsByReviewerIdAndProviderIdAndOrigin; this
-- index is the concurrent-insert backstop.
CREATE UNIQUE INDEX uq_review_organic_once
    ON reviews (reviewer_id, provider_id) WHERE origin = 'ORGANIC' AND is_deleted = false;

-- (8) The daily-cap probe's index: today's organic reviews per reviewer.
CREATE INDEX idx_reviews_organic_reviewer_day
    ON reviews (reviewer_id, created_at) WHERE origin = 'ORGANIC' AND is_deleted = false;

-- (9) The moderation queue's drain index (D-N5's complete FIFO key:
-- created_at, id), pending rows only.
CREATE INDEX idx_reviews_moderation_queue
    ON reviews (created_at, id) WHERE moderation_status = 'PENDING_REVIEW' AND is_deleted = false;

-- (10) The Envers mirror (§4.2: the new column "يدخل التعريف المُدقَّق").
ALTER TABLE reviews_aud ADD COLUMN IF NOT EXISTS origin VARCHAR(20) DEFAULT 'BOOKING';
ALTER TABLE reviews_aud ADD COLUMN IF NOT EXISTS listing_id UUID;
ALTER TABLE reviews_aud ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(20) DEFAULT 'PUBLISHED';

-- (11) §4.4 — the general (organic) rating pair on the provider profile; the
-- same listener refreshes it from the origin='ORGANIC' aggregate. Count is
-- NOT NULL DEFAULT 0 (a provider with zero organic reviews carries an exact
-- 0, not "unknown") — every existing row backfills losslessly.
ALTER TABLE provider_profiles ADD COLUMN IF NOT EXISTS rating_general_average DOUBLE PRECISION;
ALTER TABLE provider_profiles ADD COLUMN IF NOT EXISTS rating_general_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE provider_profiles_aud ADD COLUMN IF NOT EXISTS rating_general_average DOUBLE PRECISION;
ALTER TABLE provider_profiles_aud ADD COLUMN IF NOT EXISTS rating_general_count BIGINT;

-- (12) §4.5 — the daily-cap setting's seed row (the §4.1 control layer's
-- second live resident). JSON number 5; the admin write surface validates the
-- positive-integer type, the reviews gate bounds its reads. Seeded rows
-- bypass Envers by nature (the V70/V71 precedent: the mirror records the
-- first real admin write).
INSERT INTO system_settings (id, setting_key, setting_value, description)
VALUES ('72727272-7272-4272-8272-727272727272',
        'reviews.organic.daily-cap',
        '5'::jsonb,
        'Organic reviews per reviewer per rolling 24 hours (yelp plan 4.5)');
