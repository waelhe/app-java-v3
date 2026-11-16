-- A-18 (compliance plan C.12 — «نظام تحديثات المنصة», the parallel plan's
-- last Track-A unit): the platform release rows — DATA, not migrations
-- (the V70 dictionary discipline the plan's own wording pins: «صفوف إصدار
-- بيانات»). One table, one mirror, one landing:
--
-- 1) platform_releases: the release facts the manager publishes from the
--    console — channel (the ANDROID/IOS/Web closed vocabulary, CHECK-pinned)
--    · version (semver shape, CHECK-pinned) · changelog · min_version (the
--    floor below which the client must force-update) · mandatory ·
--    grace_hours («نافذة التوفير» — how long the user may defer) ·
--    published_at (the domain fact, distinct from the audit createdAt). The
--    semver column is named release_version — the plain `version` name is
--    BaseEntity's optimistic-lock column (BIGINT), and PostgreSQL rejects a
--    duplicated column name; the wire/JSON field stays "version" (the view's
--    own shape) while the STORAGE column disambiguates.
--    Every BaseEntity column lands from day one (the V70 stance), and the
--    (channel, version) identity is unique WITHOUT a soft-delete predicate:
--    a release is a historical fact — its identity is never recycled, and no
--    publish path updates or deletes a row (superseding is a new publish).
-- 2) platform_releases_aud: the Envers mirror (the V24 convention; the V33
--    lesson — base-table columns without the _aud twin break audit INSERTs
--    silently). The manager's publication is a trust-shape action on the
--    platform: who published what and when is answerable.
-- 3) The latest-read index: (channel, published_at DESC) — the public boot
--    path's single hot query (one indexed row per channel).
--
-- Checksum registered in migration-checksums.properties in this same unit
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE platform_releases (
    id           UUID PRIMARY KEY,
    channel      VARCHAR(10) NOT NULL,
    release_version VARCHAR(32) NOT NULL,
    changelog    TEXT NOT NULL,
    min_version  VARCHAR(32) NOT NULL,
    mandatory    BOOLEAN NOT NULL,
    grace_hours  INT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_platform_releases_channel
        CHECK (channel IN ('ANDROID', 'IOS', 'WEB')) NOT VALID,
    CONSTRAINT chk_platform_releases_version_shape
        CHECK (release_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$') NOT VALID,
    CONSTRAINT chk_platform_releases_min_version_shape
        CHECK (min_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$') NOT VALID,
    CONSTRAINT chk_platform_releases_grace_nonnegative
        CHECK (grace_hours >= 0) NOT VALID
);

-- The release identity: (channel, version) is unique with no soft-delete
-- predicate — the V70 identity stance verbatim (an identity value is never
-- released by deletion; a re-published version is a conflict, not a reuse).
CREATE UNIQUE INDEX uq_platform_releases_channel_version
    ON platform_releases (channel, release_version);

-- The public boot path's read: the latest published row per channel.
CREATE INDEX idx_platform_releases_channel_latest
    ON platform_releases (channel, published_at DESC);

ALTER TABLE platform_releases VALIDATE CONSTRAINT chk_platform_releases_channel;
ALTER TABLE platform_releases VALIDATE CONSTRAINT chk_platform_releases_version_shape;
ALTER TABLE platform_releases VALIDATE CONSTRAINT chk_platform_releases_min_version_shape;
ALTER TABLE platform_releases VALIDATE CONSTRAINT chk_platform_releases_grace_nonnegative;

-- The Envers mirror (V24 convention).
CREATE TABLE platform_releases_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    channel      VARCHAR(10),
    release_version VARCHAR(32),
    changelog    TEXT,
    min_version  VARCHAR(32),
    mandatory    BOOLEAN,
    grace_hours  INT,
    published_at TIMESTAMPTZ,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
