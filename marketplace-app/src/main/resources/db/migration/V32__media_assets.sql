-- Listing media assets (roadmap B1 / gap G-PROD-1 — the "photos-first" product
-- capability). Flyway owns the schema (SYSTEM.md §7); every BaseEntity column
-- present from day one (the V25 lesson); Envers audit table follows the V24
-- convention (_aud + revinfo relationship, REVTYPE 0/1/2).
--
-- Cross-module references are plain UUID columns without FK constraints to
-- provider_listings — the same decoupling as disputes.booking_id (V20): the
-- media module resolves listings through the ListingPriceProvider port, never
-- through a database-level dependency on the catalog module's table.
--
-- object_key carries a UNIQUE constraint: it is server-generated
-- (listings/{listingId}/{uuid}.{ext}) and identifies exactly one stored object.

CREATE TABLE media_assets (
    id           UUID PRIMARY KEY,
    listing_id   UUID NOT NULL,
    provider_id  UUID NOT NULL,
    object_key   VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT NOT NULL CHECK (size_bytes > 0),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED')),
    position     INT NOT NULL CHECK (position > 0),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_assets_object_key UNIQUE (object_key)
);

CREATE INDEX idx_media_assets_listing_position
    ON media_assets (listing_id, position)
    WHERE is_deleted = FALSE;
CREATE INDEX idx_media_assets_provider
    ON media_assets (provider_id)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention).
CREATE TABLE media_assets_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    listing_id   UUID,
    provider_id  UUID,
    object_key   VARCHAR(500),
    content_type VARCHAR(100),
    size_bytes   BIGINT,
    status       VARCHAR(20),
    position     INT,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
