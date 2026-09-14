-- L35 (realestate systems plan §5 — saved searches and alerts): the
-- saved-search tables. A saved search is a user's stored SearchCriteria
-- (the exact record the search surface binds — serialized as JSONB, the
-- official Hibernate JSON mapping the amenities column established in
-- V48) plus the alert flag and the match bookkeeping.
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V52/listing_leads discipline): user_id lives in
-- the users.id space and is resolved through the identity seams (the
-- /me controller stitch), never through a database-level dependency.
--
-- saved_search_matches is the idempotency ledger (the plan's criterion
-- 3-b, the CodeRabbit-adopted uniqueness key): one row per
-- (saved_search_id, listing_id) pair EVER notified, guarded by a PARTIAL
-- UNIQUE INDEX. It is an OPERATIONAL DELIVERY LEDGER, not a domain
-- aggregate — the event_publication precedent (framework-managed tables
-- with no module entity): rows are born complete and never mutated, so
-- there is no mutable state for an Envers mirror to track (its audit
-- trail is the row itself), and the matcher writes it with a native
-- INSERT ... ON CONFLICT DO NOTHING (the V52 seeding precedent) — the
-- skip is a returned 0, never a transaction-aborting 23505.
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so
-- the shared production database keeps serving traffic during the deploy.
-- Every BaseEntity column present from day one (V25/V32 lesson); Envers
-- mirrors follow the V24 convention (V33 lesson).

CREATE TABLE saved_searches (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    criteria        JSONB NOT NULL,
    alert_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    last_matched_at TIMESTAMPTZ,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT saved_searches_alert_enabled_check CHECK (alert_enabled IN (TRUE, FALSE)) NOT VALID
);

ALTER TABLE saved_searches VALIDATE CONSTRAINT saved_searches_alert_enabled_check;

-- The listener's scan index: every alert-enabled, live saved search in
-- id order (the deterministic scan cursor). Partial on the two flags the
-- scan filters by — soft-deleted and muted searches never enter the loop.
CREATE INDEX idx_saved_searches_alert_scan
    ON saved_searches (id)
    WHERE is_deleted = FALSE AND alert_enabled = TRUE;

-- The owner's listing surface (paged /me reads) in the L32 deterministic
-- order (created_at DESC, id DESC — the full ordering key, no wobbly pages).
CREATE INDEX idx_saved_searches_user_recent
    ON saved_searches (user_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The idempotency ledger (criterion 3-b) — the event_publication
-- precedent: an operational table with NO module entity and NO Envers
-- mirror (rows are born complete and never mutated — see the header).
CREATE TABLE saved_search_matches (
    id              UUID PRIMARY KEY,
    saved_search_id UUID NOT NULL,
    listing_id      UUID NOT NULL,
    matched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One notified (search, listing) pair ever — the skip key.
CREATE UNIQUE INDEX uq_saved_search_matches_once
    ON saved_search_matches (saved_search_id, listing_id);

-- Envers mirror (V24 convention) — the saved_searches entity only; the
-- matches ledger has no entity and no mutable state (see header).
CREATE TABLE saved_searches_aud (
    id              UUID NOT NULL,
    rev             BIGINT NOT NULL,
    revtype         SMALLINT,
    user_id         UUID NOT NULL,
    criteria        JSONB NOT NULL,
    alert_enabled   BOOLEAN NOT NULL,
    last_matched_at TIMESTAMPTZ,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
