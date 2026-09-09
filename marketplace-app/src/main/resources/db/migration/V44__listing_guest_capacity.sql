-- I6 (internal free plan §6, roadmap D1): guest capacity per listing.
-- One nullable column on provider_listings — max_guests:
--   NULL    = capacity not declared (legacy rows and providers that skip
--             the optional field) — such listings never match a guests
--             criterion (NULL >= N is not true in SQL), which is the honest
--             semantic: undeclared capacity cannot satisfy a requirement;
--   N > 0   = the listing accommodates N guests.
-- Positivity is enforced at three layers (the L27 type-gate philosophy):
--   1. SearchCriteria canonical constructor (guests <= 0 -> 400, in code)
--   2. Bean Validation @Positive on the write-side request records
--   3. this CHECK constraint (integrity floor for any writer)
-- Envers audit mirror gains the same column (V24 convention, the V43/V37
-- pattern for column additions) so @Audited snapshots keep writing — the
-- V33 lesson: base-table columns without the _aud twin break audit INSERTs
-- silently under mocked tests.
-- No backfill: existing rows keep NULL (undeclared) — the filter treats
-- them as non-matching when the criterion is present and byte-identically
-- when it is absent.

ALTER TABLE provider_listings
    ADD COLUMN IF NOT EXISTS max_guests INT;

ALTER TABLE provider_listings_aud
    ADD COLUMN IF NOT EXISTS max_guests INT;

ALTER TABLE provider_listings
    ADD CONSTRAINT chk_provider_listings_max_guests_positive
    CHECK (max_guests IS NULL OR max_guests > 0);
