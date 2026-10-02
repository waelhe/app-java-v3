-- W2 (yelp-level-plan §5 — the business page): the three provider-owned
-- business-page blocks the plan's roadmap row names: declared working
-- hours, the declared services list, and the declared geographic service
-- areas. Three tables, one migration — they are one concern (what the
-- owner declares about how and where the business serves).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson — every
--     column is born with the table, never added later): is_deleted,
--     version, created_by/at, updated_by/at.
--   * CHECKs in the V44 shape: NOT VALID then VALIDATE in the SAME script
--     — new tables are empty, so the validation scan is metadata-only
--     (the V84 system_settings precedent verbatim; the V56/V66 split
--     exists for tables with live rows).
--   * Partial unique indexes in the V70/V64 shape: identity predicates
--     hold over the live rows only (WHERE is_deleted = FALSE).
--   * The _aud mirrors in the V24 shape: all columns nullable (a DEL
--     revision carries (id, rev, revtype) alone).
--   * Money in the V2 house shape: integer cents + a 3-letter ISO 4217
--     currency (price_cents BIGINT + currency VARCHAR(3) — never a float;
--     "S7: the monetary shape is complete").
--   * day_of_week uses the ISO numbering (1=Monday..7=Sunday) — the
--     java.time.DayOfWeek.getValue() contract, so the entity maps the
--     standard library's own numbering with zero translation.
--
-- Access rights: the self-service writes ride the provider module's own
-- controller surface (the ProviderController precedent); the public read
-- rides the composed public page (ProviderPublicPageService).

-- ---------------------------------------------------------------------------
-- business_hours (G11): one declared window per provider per weekday.
--
-- The plan's wording: «ساعات عمل (business_hours بمفتاح فريد مزود×يوم،
-- وعرضها في openingHours النمطية)» — the unique key is provider×day,
-- exactly as allocated. The availability_slots table stays what it is
-- (bookable individual slots — the gap's own evidence); business hours
-- are the DECLARED schedule, never a booking surface.
-- ---------------------------------------------------------------------------

CREATE TABLE business_hours (
    id          UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES provider_profiles (id),
    day_of_week SMALLINT NOT NULL,
    opens_at    TIME NOT NULL,
    closes_at   TIME NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ISO weekday numbering: 1=Monday .. 7=Sunday (java.time.DayOfWeek).
    CONSTRAINT chk_business_hours_day
        CHECK (day_of_week BETWEEN 1 AND 7) NOT VALID,
    -- A window that opens after (or at) its close is a data defect, never
    -- a night-shift: a business open past midnight declares two days or
    -- the 24h form (opens 00:00, closes 23:59 — the honest approximation
    -- schema.org's own openingHours string makes).
    CONSTRAINT chk_business_hours_window
        CHECK (opens_at < closes_at) NOT VALID
);

ALTER TABLE business_hours VALIDATE CONSTRAINT chk_business_hours_day;
ALTER TABLE business_hours VALIDATE CONSTRAINT chk_business_hours_window;

-- The plan's unique key: provider × day, over the live rows (V70 shape).
CREATE UNIQUE INDEX uq_business_hours_provider_day
    ON business_hours (provider_id, day_of_week)
    WHERE is_deleted = FALSE;

-- The page's own read: one provider's week in day order.
CREATE INDEX idx_business_hours_provider_day
    ON business_hours (provider_id, day_of_week)
    WHERE is_deleted = FALSE;

CREATE TABLE business_hours_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    provider_id UUID,
    day_of_week SMALLINT,
    opens_at    TIME,
    closes_at   TIME,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------
-- provider_services (G12): the declared services list — the plan's Yelp
-- «قائمة الطعام» analog: service, duration, price.
--
-- The plan's wording: «قائمة خدمات معلنة provider_services». Declared
-- by the provider, displayed on the business page — a menu, not a
-- bookable product (booking stays on listings/availability; this is
-- display data about the business's offer).
-- ---------------------------------------------------------------------------

CREATE TABLE provider_services (
    id               UUID PRIMARY KEY,
    provider_id      UUID NOT NULL REFERENCES provider_profiles (id),
    title            VARCHAR(200) NOT NULL,
    description      VARCHAR(1000),
    duration_minutes SMALLINT,
    price_cents      BIGINT,
    currency         VARCHAR(3),
    position         INTEGER NOT NULL DEFAULT 0,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A service title is non-blank within its length class.
    CONSTRAINT chk_provider_services_title
        CHECK (length(trim(title)) BETWEEN 1 AND 200) NOT VALID,
    -- A duration, when declared, is a positive whole number of minutes.
    CONSTRAINT chk_provider_services_duration
        CHECK (duration_minutes IS NULL OR duration_minutes > 0) NOT VALID,
    -- Money in the V2 shape: non-negative cents (free-of-charge is NULL,
    -- never 0-with-currency — "price not declared" ≠ "free").
    CONSTRAINT chk_provider_services_price
        CHECK (price_cents IS NULL OR price_cents >= 0) NOT VALID,
    -- ISO 4217 alphabetic code, uppercase, exactly 3 letters — and only
    -- meaningful with an amount: a currency without a price is a defect.
    CONSTRAINT chk_provider_services_currency
        CHECK ((price_cents IS NULL AND currency IS NULL)
            OR (price_cents IS NOT NULL AND currency IS NOT NULL
                AND currency ~ '^[A-Z]{3}$')) NOT VALID,
    -- The display order key is non-negative (positions allocate from 0).
    CONSTRAINT chk_provider_services_position
        CHECK (position >= 0) NOT VALID
);

ALTER TABLE provider_services VALIDATE CONSTRAINT chk_provider_services_title;
ALTER TABLE provider_services VALIDATE CONSTRAINT chk_provider_services_duration;
ALTER TABLE provider_services VALIDATE CONSTRAINT chk_provider_services_price;
ALTER TABLE provider_services VALIDATE CONSTRAINT chk_provider_services_currency;
ALTER TABLE provider_services VALIDATE CONSTRAINT chk_provider_services_position;

-- The menu's order key: one row per position per provider over the live
-- rows — a total order the page renders deterministically (the D-N5
-- stable-pagination rule, applied to a declared list).
CREATE UNIQUE INDEX uq_provider_services_position
    ON provider_services (provider_id, position)
    WHERE is_deleted = FALSE;

CREATE TABLE provider_services_aud (
    id               UUID NOT NULL,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT,
    provider_id      UUID,
    title            VARCHAR(200),
    description      VARCHAR(1000),
    duration_minutes SMALLINT,
    price_cents      BIGINT,
    currency         VARCHAR(3),
    position         INTEGER,
    is_deleted       BOOLEAN,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------
-- service_areas (G13): the declared geographic service area — «أخدم هذه
-- المناطق». Rows point INTO the existing geo tree (geo_locations, V47):
-- the same cached tree the whole platform resolves places through, so an
-- area is a real node (city/district) — never a free-text zone name.
-- ---------------------------------------------------------------------------

CREATE TABLE service_areas (
    id          UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES provider_profiles (id),
    location_id UUID NOT NULL REFERENCES geo_locations (id),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One declared area per (provider, location) over the live rows — the
-- V64 one-report-per-target shape.
CREATE UNIQUE INDEX uq_service_areas_provider_location
    ON service_areas (provider_id, location_id)
    WHERE is_deleted = FALSE;

CREATE TABLE service_areas_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    provider_id UUID,
    location_id UUID,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
