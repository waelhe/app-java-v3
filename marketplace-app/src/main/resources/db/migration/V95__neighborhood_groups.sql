-- L51 (the Nextdoor-2026 completeness wave — gap #6, the neighbors
-- groups): the neighborhood's specialist clubs «مجموعات الجيران». The
-- community module's eighth state migration, on the V60/V61/V64/V73/
-- V83/V90 membership+posts+reports+reactions+events+market base — a
-- group is one neighborhood's standing club, a membership is one
-- member's single live seat in one live group.
--
-- The plan's own §7 gate G-N6 («المجموعات — قرار فتح نافذة كاملة»)
-- opened with the owner's standing Nextdoor-2026 directive (the wave
-- word «نفذ الموجة التالية، والفجوة 6 من المجتمع», 2026-10-02); the
-- gap analysis's #6 contract (docs/nextdoor-gap-analysis.md §3):
-- NeighborhoodGroup (§7.7/6 — name/description/members) +
-- POST /groups/{id}/membership. The club vocabulary itself is the
-- PRODUCT's own (the owner's design spec — the S10 groups screen's
-- rows and the S8 sidebar's clubs, measured verbatim into
-- R__seed_content_qudsayya.sql), so the seeded rows share one
-- membership with the frontend's display dataset, D-N7's two-sided
-- discipline.
--
-- NUMBERING (measured 2026-10-02, corrected in the review round): the
-- open branches hold the full reservation ladder — V88/V89 (W2, PR
-- #489), V90 (the market board, PR #490 — this wave rides that chain's
-- head feat/market-board @ 4f906ed), V91/V92 (W3 discovery, PR #492)
-- and V93/V94 (W4 reviewer identity, PR #494). The wave first took
-- V91 in-tree believing only the W2 reservations existed; the review
-- round's full-queue measurement found the collision with W3's V91
-- and moved this migration to V95 — the house renumber-at-merge
-- precedent (the yelp W0/W1 waves moved V71..V74 to V84..V87 at the
-- #484 merge — content never applied to production is free to
-- renumber) applies to whichever chain lands second, and this wave
-- lands after all four. The merge order V88..V95 stays strictly
-- ascending at every deploy point (out-of-order is disabled by
-- design).
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61/V73/V83/V90 discipline): location_id
-- lives in the geo_locations.id space and arrives level-checked through
-- GeoLookupPort at the SEED's own authoring time (every seeded group
-- points at the geo seed's closed level-3 skeleton) — there is no
-- group-creation write yet (a documented product decision inside the
-- opened G-N6 window), so no service-side resolve exists to carry the
-- L41 gate; the join gate reads the group's OWN location_id instead.
-- group_id is the sanctioned INTERNAL reference (the V7
-- messages.conversation_id / V61 post_comments.post_id / V73
-- post_reactions.post_id / V83 event_rsvps.event_id precedent): a
-- plain UUID column in Java, a real FK in SQL — the group and its
-- memberships are one aggregate inside the module's own boundary.
--
-- NO CHECK constraint rides this migration — and that is the measured
-- shape, not an omission: the V44 locking pattern pins ENUM
-- vocabularies (the events' categories, the market's five-chip board),
-- and the group entity carries NO enumerated column — the registered
-- contract is name/description/members, the texts are NOT NULL, and
-- the domain's ONE integrity rule is the membership uniqueness below
-- (the V64/V73/V83 partial-unique tool, not a CHECK). A CHECK born
-- without a vocabulary would be decoration, and decoration is debt.
--
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirrors follow the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- The board index is the query's own shape: location-scoped, live-only,
-- on the complete sort key (created_at ASC, id ASC — D-N5: no shaky
-- pages; the groups board is the hood's HISTORICAL order — the oldest
-- club first, the seed's own insertion order IS the design's display
-- order, and the id breaks same-second ties). The member index is
-- NON-partial on purpose (the V61/V73/V83/V90 reasoning verbatim): the
-- b-2 export and the b-3 purge scan by member THROUGH Hibernate's
-- soft-delete filter (native SQL), so a partial index would hide
-- exactly the rows those seams exist to see.
--
-- The membership unique index is the one-seat-per-member guard (the
-- V73 uq_post_reactions_post_member / V83 uq_event_rsvps_event_member
-- precedent verbatim: the service's explicit 409 comes first, the
-- constraint is the backstop — 23505 -> 409 — and a left (soft-deleted)
-- membership frees the seat for a fresh join). Its group_id-leading
-- prefix serves the grouped member count over the page's group ids
-- (the V73 one-shape-one-index-both-reads reasoning).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_groups (
    id              UUID PRIMARY KEY,
    location_id     UUID NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The board query's own index (D-N5): location-scoped, live-only, on
-- the complete sort key — created_at ASC, id ASC — so a page boundary
-- is stable even when two clubs are seeded in the same second, and
-- the hood's historical order (the seed's own order) is the board's
-- order.
CREATE INDEX idx_neighborhood_groups_board
    ON neighborhood_groups (location_id, created_at ASC, id ASC)
    WHERE is_deleted = FALSE;

CREATE TABLE neighborhood_group_memberships (
    id         UUID PRIMARY KEY,
    group_id   UUID NOT NULL REFERENCES neighborhood_groups(id),
    member_id  UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The product's own one membership per member per group: one LIVE row
-- per member per group — the explicit 409 first, this constraint the
-- backstop (the V64/V73/V83 precedent verbatim), and a left
-- (soft-deleted) membership frees the seat for a fresh join.
CREATE UNIQUE INDEX uq_neighborhood_group_memberships_group_member
    ON neighborhood_group_memberships (group_id, member_id)
    WHERE is_deleted = FALSE;

-- The member seam (b-2 export / b-3 purge) rides a NON-partial index
-- (the V73 idx_post_reactions_member / V83 idx_event_rsvps_member
-- reasoning verbatim).
CREATE INDEX idx_neighborhood_group_memberships_member
    ON neighborhood_group_memberships (member_id, created_at, id);

-- Envers audit history (V24 convention) — every group's lifecycle and
-- every membership taken or left is a revision; the export surface
-- reads it.
CREATE TABLE neighborhood_groups_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    location_id UUID,
    name        VARCHAR(200),
    description TEXT,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE neighborhood_group_memberships_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    group_id   UUID,
    member_id  UUID,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
