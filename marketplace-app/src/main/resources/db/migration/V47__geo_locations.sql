-- Hierarchical administrative geography (realestate systems plan §5, L30 —
-- D-R1: administrative hierarchy, NOT coordinates; radius search is excluded
-- by the measured PostGIS platform gate, V34 + SYSTEM.md §15).
--
-- The tree: country (0) -> governorate (1) -> city (2) -> neighborhood (3).
-- parent_id is a plain self-reference (no FK constraint — the same
-- decoupling discipline the house applies everywhere: integrity is owned by
-- the module's service gate + these row-local CHECKs; a CHECK cannot assert
-- another row's level, so the parent-level rule is the factory gate and the
-- V47 CHECKs pin what a row can assert on its own:
--   * level range 0..3
--   * root consistency: level 0 <=> parent IS NULL
--
-- Every BaseEntity column present from day one (the V25/V32 lesson) and the
-- Envers mirror follows the V24 convention (the V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- Constraint locking (the V44 pattern — CodeRabbit #268): CHECK constraints
-- are added NOT VALID (metadata-only, enforced for new rows immediately)
-- then VALIDATEd under the weaker SHARE UPDATE EXCLUSIVE lock so the shared
-- database keeps serving reads/writes during the Railway deploy window.

CREATE TABLE geo_locations (
    id         UUID PRIMARY KEY,
    parent_id  UUID,
    level      SMALLINT NOT NULL,
    name_ar    VARCHAR(100) NOT NULL,
    name_en    VARCHAR(100),
    slug       VARCHAR(120) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_geo_locations_slug UNIQUE (slug),
    CONSTRAINT chk_geo_locations_level_range CHECK (level BETWEEN 0 AND 3),
    CONSTRAINT chk_geo_locations_root_shape
        CHECK ((level = 0 AND parent_id IS NULL) OR (level > 0 AND parent_id IS NOT NULL))
        NOT VALID
);

-- Child lookups (the children endpoint) and autocomplete prefix scans.
CREATE INDEX idx_geo_locations_parent ON geo_locations (parent_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_geo_locations_name_ar ON geo_locations (name_ar) WHERE is_deleted = FALSE;
CREATE INDEX idx_geo_locations_slug ON geo_locations (slug) WHERE is_deleted = FALSE;

ALTER TABLE geo_locations VALIDATE CONSTRAINT chk_geo_locations_root_shape;

-- Envers audit history (V24 convention; the seed itself bypasses Envers by
-- nature — the documented plan debt D-E6, closed at the first real admin
-- write which this mirror records).
CREATE TABLE geo_locations_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    parent_id  UUID,
    level      SMALLINT,
    name_ar    VARCHAR(100),
    name_en    VARCHAR(100),
    slug       VARCHAR(120),
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
