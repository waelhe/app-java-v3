-- L41 (neighborhood community plan §5 — the membership anchor): the
-- community module's first migration. The vertical-module pattern of
-- marketplace-media (V32) and marketplace-realestate (V48), keeping the
-- user's community state in its own domain table.
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V48/property_details, V52/listing_leads and
-- V54/saved_searches discipline): user_id lives in the users.id space
-- and arrives through the identity seams (the /me controller stitch);
-- location_id lives in the geo_locations.id space and is resolved and
-- LEVEL-CHECKED through the shared GeoLookupPort before any write
-- (D-N2: the neighborhood IS a level-3 node of the ONE administrative
-- hierarchy — no parallel geography, so no geo module dependency).
--
-- G-N1's conservative default (one home neighborhood per user) is the
-- PARTIAL UNIQUE INDEX below — the V47 slug precedent's own mechanism:
-- a soft-deleted (left) membership releases the slot, so leaving and
-- rejoining works, while two ACTIVE memberships for one user are
-- structurally impossible (the 23505 backstop maps the race to 409
-- through the shared handler).
--
-- verification_state is SELF_DECLARED only (D-N3): VERIFIED is reserved
-- behind gate G-N2 (the verification-method product decision) and does
-- NOT exist in the column's vocabulary yet — the CHECK pins the floor
-- for raw writers too; opening the gate widens it in a new migration.
--
-- CHECK in the V44 locking shape: NOT VALID (metadata-only, enforced for
-- new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so the
-- shared production database keeps serving traffic during the deploy.
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirror follows the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_memberships (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL,
    location_id        UUID NOT NULL,
    verification_state VARCHAR(20) NOT NULL,
    member_since       TIMESTAMPTZ NOT NULL,
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_neighborhood_memberships_verification_state
        CHECK (verification_state IN ('SELF_DECLARED')) NOT VALID
);

ALTER TABLE neighborhood_memberships
    VALIDATE CONSTRAINT chk_neighborhood_memberships_verification_state;

-- G-N1 default: exactly ONE active membership per user. Partial (the V47
-- slug mechanism): a left membership (is_deleted = TRUE) releases the
-- slot for the rejoin; the switch's delete-then-insert rides the
-- service's explicit flush ordering (see NeighborhoodMembershipService's
-- javadoc — Hibernate flushes inserts before updates).
CREATE UNIQUE INDEX uq_neighborhood_memberships_active_user
    ON neighborhood_memberships (user_id) WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention) — every join, switch and leave
-- is a revision; the moderation/export surfaces read it.
CREATE TABLE neighborhood_memberships_aud (
    id                 UUID NOT NULL,
    rev                INTEGER NOT NULL,
    revtype            SMALLINT,
    user_id            UUID,
    location_id        UUID,
    verification_state VARCHAR(20),
    member_since       TIMESTAMPTZ,
    is_deleted         BOOLEAN,
    version            BIGINT,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
