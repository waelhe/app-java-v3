-- L33 (realestate systems plan §5): the classifieds lifecycle — expiry and
-- renewal on the EXISTING state machine (D-R7: zero changes to
-- ListingStatus.TRANSITIONS — the measured transitions already allow
-- ACTIVE→PAUSED and PAUSED→ACTIVE).
--
-- Three nullable columns on provider_listings (no backfill — D-E7
-- retro-compatibility: existing rows keep NULL = never expire, the
-- pre-layer behavior byte-identical):
--   expires_at    — when the listing's publication ends. NULL on legacy
--                   rows = immortal by data; NEW activations must set it
--                   (the service policy: explicit date or the configured
--                   expiry-days — a 409 otherwise, "no silently-immortal
--                   listing");
--   paused_reason — the DURABLE marker (CodeRabbit-adopted note 3 of the
--                   plan's review): MANUAL (provider-initiated pause) vs
--                   EXPIRED (the job). Renewal works ONLY on EXPIRED —
--                   the two paths never mix; a provider who paused
--                   deliberately stays paused until they re-activate
--                   through the existing path;
--   renewed_at    — the cooldown's measurement anchor (the plan's
--                   "one renewal every N days" needs to know the last
--                   renewal; deriving it from the audit trail would
--                   couple business policy to audit infrastructure).
--
-- Constraint locking: the V44 pattern (NOT VALID + VALIDATE) so the
-- shared production database keeps serving traffic during the deploy.
-- The Envers mirror gains the same three columns (V24 convention, the
-- V33/V44 lesson).

ALTER TABLE provider_listings ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE provider_listings ADD COLUMN IF NOT EXISTS paused_reason VARCHAR(20);
ALTER TABLE provider_listings ADD COLUMN IF NOT EXISTS renewed_at TIMESTAMPTZ;

ALTER TABLE provider_listings_aud ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE provider_listings_aud ADD COLUMN IF NOT EXISTS paused_reason VARCHAR(20);
ALTER TABLE provider_listings_aud ADD COLUMN IF NOT EXISTS renewed_at TIMESTAMPTZ;

ALTER TABLE provider_listings
    ADD CONSTRAINT chk_provider_listings_paused_reason_domain
    CHECK (paused_reason IS NULL OR paused_reason IN ('MANUAL', 'EXPIRED')) NOT VALID;
ALTER TABLE provider_listings
    VALIDATE CONSTRAINT chk_provider_listings_paused_reason_domain;

-- The expiry job scans ACTIVE rows whose expiry passed — a partial index
-- keeps that scan narrow.
CREATE INDEX idx_provider_listings_expiry
    ON provider_listings (expires_at)
    WHERE is_deleted = FALSE AND status = 'ACTIVE' AND expires_at IS NOT NULL;
