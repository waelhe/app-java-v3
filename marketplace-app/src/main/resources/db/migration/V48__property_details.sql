-- Real-estate vertical fields (realestate systems plan §5, L31 — D-E2: the
-- vertical-module pattern of marketplace-media, keeping provider_listings
-- domain-neutral: catalog owns the listing's life/price/category, this
-- module owns the property-specific field set).
--
-- Cross-module references are plain UUID columns without FK constraints to
-- provider_listings (the V32/media_assets and V20/disputes discipline):
-- the realestate module resolves listings through the shared ports and the
-- catalog-spi named interface, never through a database-level dependency.
-- listing_id is UNIQUE — one property block per listing (the upsert flow +
-- this constraint; the 23505 backstop maps to 409 through the handler).
--
-- Every BaseEntity column present from day one (V25/V32 lesson); Envers
-- mirror follows the V24 convention (V33 lesson).
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced for
-- new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so the
-- shared production database keeps serving traffic during the deploy.

CREATE TABLE property_details (
    id             UUID PRIMARY KEY,
    listing_id     UUID NOT NULL,
    provider_id    UUID NOT NULL,
    purpose        VARCHAR(10) NOT NULL,
    property_type  VARCHAR(20) NOT NULL,
    area_m2        INT,
    rooms          INT,
    bathrooms      INT,
    floor_number   INT,
    total_floors   INT,
    building_year  INT,
    furnished      BOOLEAN,
    amenities      JSONB,
    available_from DATE,
    location_id    UUID,
    latitude       NUMERIC(9, 6),
    longitude      NUMERIC(9, 6),
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    version        BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_property_details_listing_id UNIQUE (listing_id),
    CONSTRAINT chk_property_details_purpose CHECK (purpose IN ('RENT', 'SALE')),
    CONSTRAINT chk_property_details_type CHECK (property_type IN
        ('APARTMENT', 'VILLA', 'LAND', 'SHOP', 'OFFICE', 'GARAGE')),
    CONSTRAINT chk_property_details_area_positive
        CHECK (area_m2 IS NULL OR area_m2 > 0) NOT VALID,
    CONSTRAINT chk_property_details_rooms_positive
        CHECK (rooms IS NULL OR rooms > 0) NOT VALID,
    CONSTRAINT chk_property_details_bathrooms_positive
        CHECK (bathrooms IS NULL OR bathrooms > 0) NOT VALID,
    CONSTRAINT chk_property_details_floor_le_total
        CHECK (floor_number IS NULL OR total_floors IS NULL OR floor_number <= total_floors) NOT VALID,
    CONSTRAINT chk_property_details_building_year_range
        CHECK (building_year IS NULL OR building_year BETWEEN 1800 AND 2100) NOT VALID,
    CONSTRAINT chk_property_details_latitude_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90) NOT VALID,
    CONSTRAINT chk_property_details_longitude_range
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180) NOT VALID
);

ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_area_positive;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_rooms_positive;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_bathrooms_positive;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_floor_le_total;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_building_year_range;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_latitude_range;
ALTER TABLE property_details VALIDATE CONSTRAINT chk_property_details_longitude_range;

-- The L32 filter port scans by (location_id) and (purpose/type) over
-- not-deleted rows only.
CREATE INDEX idx_property_details_location ON property_details (location_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_property_details_kind ON property_details (property_type, purpose) WHERE is_deleted = FALSE;
CREATE INDEX idx_property_details_provider ON property_details (provider_id) WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention).
CREATE TABLE property_details_aud (
    id             UUID NOT NULL,
    rev            INTEGER NOT NULL,
    revtype        SMALLINT,
    listing_id     UUID,
    provider_id    UUID,
    purpose        VARCHAR(10),
    property_type  VARCHAR(20),
    area_m2        INT,
    rooms          INT,
    bathrooms      INT,
    floor_number   INT,
    total_floors   INT,
    building_year  INT,
    furnished      BOOLEAN,
    amenities      JSONB,
    available_from DATE,
    location_id    UUID,
    latitude       NUMERIC(9, 6),
    longitude      NUMERIC(9, 6),
    is_deleted     BOOLEAN,
    version        BIGINT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
