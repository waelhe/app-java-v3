-- L52 (the Nextdoor-2026 completeness wave — gap #7, the polls):
-- the neighborhood's live questions «استطلاعات الرأي». The community
-- module's ninth state migration, on the V60/V61/V64/V73/V83/V90/V91
-- membership+posts+reports+reactions+events+market+groups base — a
-- poll is one neighborhood's ONE-question ballot, an option is one
-- authored answer choice, a vote is one member's single live seat on
-- one poll.
--
-- The gap analysis's own registered contract
-- (docs/nextdoor-gap-analysis.md §3, gap #7): NeighborhoodPoll +
-- POST /polls/{id}/vote; the frontend's registered shape
-- (web-marketplace docs/product-charter.md §7/7-4 — the S8 featured
-- zone's interactive poll): question/options/votes, «الكتابة الحقيقية
-- = صوت واحد لكل عضو». The demo poll's own vocabulary (the mamsha
-- hours question with its three options) is measured verbatim into
-- R__seed_content_qudsayya.sql — the seeded rows share one shape with
-- the frontend's display dataset, D-N7's two-sided discipline.
--
-- NUMBERING (measured 2026-10-03): the closed ladder ends at V99
-- (SYSTEM.md's own inventory — no open branch holds a migration
-- beyond it; the coordination ladder 88/89/92/95 retired with the
-- four-merge wave), so V100 follows in-tree with no collision to
-- coordinate — the first migration of the post-queue era.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61/V73/V83/V90/V91 discipline): location_id
-- lives in the geo_locations.id space and arrives level-checked through
-- GeoLookupPort at the CREATE write's own authoring time (the L41
-- publish gate verbatim — the events'/market items' own shape); the
-- vote gate reads the stored fact instead of re-resolving it (the L51
-- join's own reasoning). poll_id and option_id are the sanctioned
-- INTERNAL references (the V7 messages.conversation_id / V61
-- post_comments.post_id / V73 post_reactions.post_id / V83
-- event_rsvps.event_id / V91 memberships.group_id precedent): plain
-- UUID columns in Java, real FKs in SQL — the poll, its options and
-- the votes are one aggregate inside the module's own boundary.
--
-- NO vocabulary CHECK rides this migration — and that is the measured
-- shape, not an omission (the V91 reasoning verbatim): the registered
-- contract carries question/options/author with NO enumerated column,
-- so the V44 locking pattern has no vocabulary to pin. The ONE
-- integrity rule the domain owns is the vote uniqueness below (the
-- V64/V73/V83/V91 partial-unique tool). The option's position floor
-- (a non-negative ordinal — the author's own submission order) keeps
-- its CHECK the way V99's score floor does: NOT VALID + inline
-- VALIDATE over a fresh table is metadata-only.
--
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirrors follow the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- The board index is the query's own shape: location-scoped, live-only,
-- on the complete sort key (created_at DESC, id DESC — D-N5: no shaky
-- pages; the polls board is the market board's own newest-first
-- discipline — the featured zone carries the LATEST poll, and the id
-- breaks same-second ties). The option index carries the option
-- batch's own order (poll_id, position) so the board's one IN read
-- composes the author's own display order. The vote counts read
-- (grouped per option over the page's option ids) rides the
-- option_id-leading partial index below; the member index is
-- NON-partial on purpose (the V61/V73/V83/V90/V91 reasoning
-- verbatim): the b-2 export and the b-3 purge scan by member THROUGH
-- Hibernate's soft-delete filter (native SQL), so a partial index
-- would hide exactly the rows those seams exist to see.
--
-- The vote unique index is the one-vote-per-member guard (the
-- uq_post_reactions_post_member / uq_event_rsvps_event_member /
-- uq_neighborhood_group_memberships_group_member precedent verbatim:
-- the service's explicit 409 comes first, the constraint is the
-- backstop — 23505 -> 409 — and a withdrawn (soft-deleted) vote
-- frees the member to vote again).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_polls (
    id              UUID PRIMARY KEY,
    location_id     UUID NOT NULL,
    question        VARCHAR(200) NOT NULL,
    author_label    VARCHAR(200) NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The board query's own index (D-N5): location-scoped, live-only, on
-- the complete sort key — created_at DESC, id DESC — so a page
-- boundary is stable even when two polls are authored in the same
-- second, and the board's newest-first order (the featured zone's
-- LATEST poll) is the index's own order.
CREATE INDEX idx_neighborhood_polls_board
    ON neighborhood_polls (location_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE TABLE neighborhood_poll_options (
    id         UUID PRIMARY KEY,
    poll_id    UUID NOT NULL REFERENCES neighborhood_polls(id),
    label      VARCHAR(200) NOT NULL,
    position   INT NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (position >= 0) NOT VALID
);

-- The option batch's own index: the board read's one IN read over the
-- page's poll ids composes each poll's options in the author's own
-- (position, id) order off this index.
CREATE INDEX idx_neighborhood_poll_options_poll
    ON neighborhood_poll_options (poll_id, position, id)
    WHERE is_deleted = FALSE;

-- Fresh table, zero rows: the ordinal floor validates inline
-- (metadata-only — the V78/V99 scale-class decision).
ALTER TABLE neighborhood_poll_options VALIDATE CONSTRAINT
    neighborhood_poll_options_position_check;

CREATE TABLE neighborhood_poll_votes (
    id         UUID PRIMARY KEY,
    poll_id    UUID NOT NULL REFERENCES neighborhood_polls(id),
    option_id  UUID NOT NULL REFERENCES neighborhood_poll_options(id),
    member_id  UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The registered contract's own one vote per member per poll: one
-- LIVE row per member per poll — the explicit 409 first, this
-- constraint the backstop (the V64/V73/V83/V91 precedent verbatim),
-- and a withdrawn (soft-deleted) vote frees the member to vote again.
CREATE UNIQUE INDEX uq_neighborhood_poll_votes_poll_member
    ON neighborhood_poll_votes (poll_id, member_id)
    WHERE is_deleted = FALSE;

-- The board's grouped count read: live votes grouped per OPTION over
-- the page's option ids (the percentage bars' own denominators — the
-- option_id-leading partial index serves the aggregate).
CREATE INDEX idx_neighborhood_poll_votes_option
    ON neighborhood_poll_votes (option_id)
    WHERE is_deleted = FALSE;

-- The member seam (b-2 export / b-3 purge) rides a NON-partial index
-- (the idx_post_reactions_member / idx_event_rsvps_member /
-- idx_neighborhood_group_memberships_member reasoning verbatim).
CREATE INDEX idx_neighborhood_poll_votes_member
    ON neighborhood_poll_votes (member_id, created_at, id);

-- Envers audit history (V24 convention) — every poll's lifecycle,
-- every option authored with it, and every vote cast or withdrawn is
-- a revision; the export surface reads it.
CREATE TABLE neighborhood_polls_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    location_id UUID,
    question    VARCHAR(200),
    author_label VARCHAR(200),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE neighborhood_poll_options_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    poll_id    UUID,
    label      VARCHAR(200),
    position   INT,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE neighborhood_poll_votes_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    poll_id    UUID,
    option_id  UUID,
    member_id  UUID,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
