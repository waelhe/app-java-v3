-- L26 (feature-expansion roadmap §5, Week 3): dynamic pricing — the price
-- calendar per listing. (a) The weekend rule: ONE row per listing carrying a
-- multiplier applied to the base price on weekend days. (b) The seasonal
-- ranges: absolute prices over [from_date, to_date) with an EXCLUSIVE end —
-- the same interval convention as the stay window (L27) and the stats
-- window (L25).
--
-- Precedence (the roadmap's single governing rule, enforced by
-- PricingService.effectiveTotalCents, not by the schema): a covering
-- seasonal range's absolute price REPLACES the base price for its days and
-- the weekend multiplier never stacks on top of it; outside the ranges the
-- multiplier applies to the base price. Overlap of seasonal ranges for one
-- listing is rejected with 409 at the service seam (adjacent ranges sharing
-- a boundary are legal — open intervals); the schema therefore carries no
-- exclusion constraint, only the shape checks.
--
-- Soft-delete interplay (the BaseEntity @SoftDelete rule — Hibernate 7
-- converts DELETE to UPDATE is_deleted=true and hides such rows from every
-- query): a FULL UNIQUE(listing_id) on the weekend rules would collide on
-- the re-create-after-delete upsert (the hidden row still occupies the
-- unique key and the INSERT fails with 23505 → a 500). The one-per-listing
-- invariant is therefore a PARTIAL UNIQUE INDEX over the live rows only
-- (WHERE is_deleted = FALSE) — the PostgreSQL mechanism for exactly this
-- pattern; the entity mapping deliberately declares no unique=true (the
-- schema truth is this index, not a table constraint).
--
-- Version note: the roadmap text says "V40" but V40 was consumed by L22
-- (notification preferences) and V39 by PR #253 — the same documented
-- reservation rule that moved L22 to V40 (PROJECT_MAP, PR #254 body):
-- V41 is the next free number on the chain.
--
-- Cross-module references are plain UUID columns without FK constraints to
-- provider_listings — the same decoupling as media_assets.listing_id (V32):
-- the pricing module resolves listings through the ListingPriceProvider
-- port, never through a database-level dependency on the catalog module's
-- table.
--
-- Every BaseEntity column present from day one (the V25 lesson); the Envers
-- audit tables follow the V24 convention (_aud + revinfo, REVTYPE 0/1/2).

CREATE TABLE listing_weekend_rules (
    id         UUID PRIMARY KEY,
    listing_id UUID NOT NULL,
    multiplier NUMERIC(6, 3) NOT NULL CHECK (multiplier > 0),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One LIVE weekend rule per listing — partial (not full) unique index: the
-- soft-deleted rows keep their history without blocking re-creation.
CREATE UNIQUE INDEX uq_listing_weekend_rules_live_listing
    ON listing_weekend_rules (listing_id)
    WHERE is_deleted = FALSE;

CREATE TABLE seasonal_rates (
    id          UUID PRIMARY KEY,
    listing_id  UUID NOT NULL,
    from_date   DATE NOT NULL,
    to_date     DATE NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_seasonal_rates_range CHECK (from_date < to_date)
);

CREATE INDEX idx_seasonal_rates_listing_range
    ON seasonal_rates (listing_id, from_date, to_date)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention).
CREATE TABLE listing_weekend_rules_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    listing_id UUID,
    multiplier NUMERIC(6, 3),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE seasonal_rates_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    listing_id  UUID,
    from_date   DATE,
    to_date     DATE,
    price_cents BIGINT,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
