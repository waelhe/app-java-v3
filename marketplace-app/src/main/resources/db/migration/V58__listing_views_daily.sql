-- L40 (realestate systems plan §5 — view analytics): the daily listing
-- views aggregate.
--
-- One row per (listing, UTC day) carries that day's deduplicated view
-- count — the plan's own shape: "جدول تجميعي listing_views_daily
-- (listing_id, view_date, count)". The count column is named view_count:
-- the plan's informal "count" collides with the SQL aggregate function in
-- every query that reads the table (SUM(count)); view_count is the same
-- fact with an unambiguous name.
--
-- The increment is the house stored-aggregate pattern (L21
-- refreshRatingAverage): a pessimistic-locked read-modify-write through
-- the entity, NOT a native INSERT .. ON CONFLICT DO UPDATE — Envers only
-- records entity operations (the D-R8 mirror must be LIVE for this
-- aggregate "تجميعي يُدقَّق كالكيانات"), and a native upsert bypasses the
-- listeners entirely, leaving the _aud mirror a dead empty shell. The
-- atomicity the plan's upsert wording exists for is preserved by the
-- UNIQUE (listing_id, view_date) constraint (the insert race resolves to
-- a constraint violation caught and retried in a new transaction — a
-- PostgreSQL transaction is aborted after a constraint violation, so the
-- retry cannot share it) plus the row lock serializing concurrent +1s.
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V20/disputes and V48/property_details discipline):
-- even though provider_listings lives in the same catalog module, the
-- module resolves listings through its own code, never through a
-- database-level dependency — the L34 listing_leads shape one module over.
--
-- view_date is the UTC day (the house timestamps are UTC instants; the
-- container clock is UTC) — the dedup window in Redis is TTL-based
-- (24h from the FIRST view of that visitor), so the day bucket and the
-- dedup marker can disagree by at most a few hours at the boundary: the
-- plan's own documented choice ("Redis TTL يوم"), noise-reduction over
-- exact calendar accounting.
--
-- CHECKs in the V44 locking shape: NOT VALID + immediate VALIDATE — valid
-- here in ONE migration (the V52 listing_leads precedent) because the
-- table is brand-new and empty, so the validation scan has nothing to
-- read and the ACCESS EXCLUSIVE window is empty (the V56/V57 split exists
-- for tables with live rows). Every BaseEntity column present from day
-- one (V25/V32 lesson); Envers mirror follows the V24 convention.

CREATE TABLE listing_views_daily (
    id              UUID PRIMARY KEY,
    listing_id      UUID NOT NULL,
    view_date       DATE NOT NULL,
    view_count      BIGINT NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_listing_views_daily_listing_date UNIQUE (listing_id, view_date),
    CONSTRAINT chk_listing_views_daily_count CHECK (view_count >= 1) NOT VALID
) ;

ALTER TABLE listing_views_daily VALIDATE CONSTRAINT chk_listing_views_daily_count;

-- The provider analytics read: the aggregate query joins on listing_id
-- (the UNIQUE constraint above is exactly that btree) and filters by the
-- provider through provider_listings' own provider_id index — no
-- additional index earns its keep (the D-I/D-E discipline: no speculative
-- indexes; measure first).

-- Envers audit history (V24 convention): every ADD (first view of a
-- listing-day) and MOD (each +1) leaves a revision — the live mirror
-- D-R8 demands for the aggregate.
CREATE TABLE listing_views_daily_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    listing_id      UUID,
    view_date       DATE,
    view_count      BIGINT,
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
