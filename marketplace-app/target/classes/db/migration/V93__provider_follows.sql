-- W4 (yelp-level plan §5 — the reviewer identity & engagement wave, G21):
-- (a) provider_follows — one member's follow of one provider. Both columns
--     are plain UUIDs in the users.id space (the V32/media_assets,
--     V52/listing_leads, V54/saved_searches discipline): no FK across
--     module borders; the write path resolves the client-facing provider
--     PROFILE id to the USER id through ProviderLookupPort (the W1
--     createOrganic seam), and provider_user_id is exactly the id
--     ListingActivatedEvent carries (A1: provider_listings.provider_id
--     references users(id)) so the activation bridge joins directly.
--     Every BaseEntity column present from day one (the V25/V32 lesson).
-- (b) provider_follow_alerts — the idempotency ledger of the follow
--     notification (the V54/saved_search_matches precedent verbatim): one
--     row per (user, listing) pair EVER alerted, guarded by a UNIQUE
--     index; an OPERATIONAL DELIVERY LEDGER, not a domain aggregate (the
--     event_publication precedent — framework-managed tables with no
--     module entity): rows are born complete and never mutated, so there
--     is no mutable state for an Envers mirror to track (its audit trail
--     is the row itself), and the bridge writes it with a native
--     INSERT ... ON CONFLICT DO NOTHING — the skip is a returned 0, never
--     a transaction-aborting 23505. The key is the pair the plan's own
--     guarantee names ("المتابعة تطلق تنبيهًا واحدًا" — one alert per
--     FOLLOWER per listing announcement), so follow-row churn (unfollow /
--     re-follow) can never re-open an already-delivered alert.
-- (c) the notification_preferences type CHECK widening to the ninth type
--     FOLLOWED_PROVIDER_NEW_LISTING — the V74 form (DROP IF EXISTS +
--     ADD NOT VALID, metadata-only, enforced for new rows immediately);
--     the VALIDATE step rides its OWN migration (V94) so its scan runs
--     under SHARE UPDATE EXCLUSIVE alone (the V66/V75 measured lesson:
--     inside one transaction the DROP/ADD statements' ACCESS EXCLUSIVE
--     lock would still be held during the validation scan, blocking live
--     reads and writes of the delivery gate every notification consults).
-- Checksums registered in migration-checksums.properties in this same PR.

CREATE TABLE provider_follows (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL,
    provider_user_id  UUID NOT NULL,
    is_deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    version           BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One live follow per (member, provider) — the service's explicit 409
-- first, this partial unique index the concurrent-insert backstop; the
-- soft delete frees the pair for a legitimate re-follow (the
-- uq_review_votes_review_voter twin).
CREATE UNIQUE INDEX uq_provider_follows_once
    ON provider_follows (user_id, provider_user_id) WHERE is_deleted = FALSE;

-- The activation bridge's scan: every live follow of one provider (the
-- ListingActivatedEvent.providerId join key, A1).
CREATE INDEX idx_provider_follows_provider_scan
    ON provider_follows (provider_user_id) WHERE is_deleted = FALSE;

-- The member's "my follows" page in the L32 deterministic order (the full
-- ordering key, no wobbly pages).
CREATE INDEX idx_provider_follows_user_recent
    ON provider_follows (user_id, created_at DESC, id DESC) WHERE is_deleted = FALSE;

-- Envers mirror (V24 convention) — all columns NULLABLE exactly as V24
-- made them: a DEL revision row carries only (id, rev, revtype); NOT NULL
-- on a DEL-revision column makes every soft delete a 500.
CREATE TABLE provider_follows_aud (
    id                UUID NOT NULL,
    rev               INTEGER NOT NULL,
    revtype           SMALLINT,
    user_id           UUID,
    provider_user_id  UUID,
    is_deleted        BOOLEAN,
    version           BIGINT,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ,
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- The idempotency ledger (the saved_search_matches precedent) — an
-- operational table with NO module entity and NO Envers mirror (rows are
-- born complete and never mutated — see the header).
CREATE TABLE provider_follow_alerts (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    listing_id  UUID NOT NULL,
    alerted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One alerted (follower, listing) pair ever — the skip key.
CREATE UNIQUE INDEX uq_provider_follow_alerts_once
    ON provider_follow_alerts (user_id, listing_id);

-- The ninth notification type joins the DB-side membership guard (the
-- V53/V55/V62/V63/V65/V74 family, one type later): NotificationType stays
-- the single source of truth for the Java side, this constraint for the
-- SQL side (the D-N7 discipline). NOT VALID here; VALIDATE in V94.
ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING')) NOT VALID;
