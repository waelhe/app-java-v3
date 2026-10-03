-- W3 (yelp-level-plan §5 — the discovery & ranking wave, G19): the
-- member's saved listings — «حفظ لاحقًا» / «قائمتي». One row per
-- (member, listing) over the live rows: a favorite is a claimed
-- relation, withdrawn by soft delete (the house retention — the row
-- stays for the audit trail, the reads stop returning it).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECKs in the V44 locking shape: NOT VALID + inline VALIDATE —
--     the table is brand-new and empty (the V52/V58 precedent for new
--     tables; the V56/V66 split exists for tables with live rows).
--   * Partial unique index in the V70/V64 shape: identity over the live
--     rows only (WHERE is_deleted = FALSE) — a withdrawn favorite can
--     be re-saved as a fresh row without colliding with its own tombstone.
--   * The _aud mirror in the V24 shape: all columns nullable.
--
-- The plan's own numbering note: this wave's migrations take the
-- next-free numbers at merge time (V91/V92 here — V88/V89 are W2's and
-- V90 is the market board's, in any merge order the numbers never
-- collide because the contents differ).
--
-- Access rights: the save/unsave/read surface rides the catalog module
-- (the listing's own module owns its member relations — the
-- listing_views_daily home); the endpoints are the member's own
-- (/api/v1/me/favorites — the SavedSearch home precedent).

CREATE TABLE listing_favorites (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    listing_id  UUID NOT NULL REFERENCES provider_listings (id),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One live favorite per (member, listing) — the partial-unique identity
-- (the V70 shape): the second save of the SAME listing answers the
-- conflict loudly at the service boundary (the read form below), and a
-- withdrawn row never blocks its own re-save.
CREATE UNIQUE INDEX uq_listing_favorites_user_listing
    ON listing_favorites (user_id, listing_id)
    WHERE is_deleted = FALSE;

-- The member's own read: my saved listings, newest-saved first (the
-- complete (created_at, id) key keeps the page boundary stable — D-N5).
CREATE INDEX idx_listing_favorites_user_saved
    ON listing_favorites (user_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every save (ADD) and withdraw
-- (DEL) leaves a revision — the member's own data trail (b-2/b-5: the
-- export and purge seams read the author's rows through the same
-- non-filtered channel every authored table uses).
CREATE TABLE listing_favorites_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    user_id     UUID,
    listing_id  UUID,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
