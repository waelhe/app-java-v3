-- L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
-- the neighborhood's structured gatherings. The community module's
-- sixth state migration, on the V60/V61/V64/V73 membership+posts+
-- reports+reactions base — an event is one member's organized gathering
-- in exactly one neighborhood, an RSVP is one member's single live
-- seat on one live event.
--
-- The plan's own §7 gate («الأحداث المهيكلة — طلب منتج فعلي») opened
-- with the owner's Nextdoor-2026 directive; the gap analysis's #4
-- contract (docs/nextdoor-gap-analysis.md §3): NeighborhoodEvent CRUD +
-- POST /events/{id}/rsvp (سعة/حضور/تسجيل). The category and
-- registration vocabularies are the PRODUCT's own (the frontend
-- contract src/lib/neighborhood-events.ts — EVENT_CATEGORIES and
-- EVENT_REGISTRATIONS measured verbatim), so the DB enumerations and
-- the Java enums share one membership, D-N7's two-sided discipline.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61/V73 discipline): author_id lives in the
-- users.id space and arrives through the identity seams, location_id in
-- the geo_locations.id space resolved and LEVEL-CHECKED through
-- GeoLookupPort before any write (D-N2). event_id is the sanctioned
-- INTERNAL reference (the V7 messages.conversation_id / V61
-- post_comments.post_id / V73 post_reactions.post_id precedent): a
-- plain UUID column in Java, a real FK in SQL — the event and its
-- RSVPs are one aggregate inside the module's own boundary.
--
-- The registration/capacity pair is ONE integrity rule, not two: the
-- product's three states («مفتوح للجميع» / «مقاعد محدودة» / «حجز
-- طاولات») pin capacity's very meaning — OPEN means no capacity to
-- count, the two seated states mean a strictly positive one. The
-- service validates the same rule BEFORE any write (the friendly 400);
-- this CHECK is the backstop (D-N7's shape). ends_at rides the honest
-- time rule: absent, or strictly after starts_at.
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE —
-- the tables are BORN EMPTY in this transaction (zero rows to scan,
-- the V61/V73 same-transaction freedom), so the validation steps cost
-- nothing while keeping the production deploy lock-honest.
--
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirrors follow the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- The board index is the query's own shape: location-scoped, live-only,
-- on the complete sort key (starts_at ASC, id ASC — D-N5: no shaky
-- pages; the events board is FORWARD-LOOKING, so the time key leads
-- where the feed's created_at led). The author index is NON-partial on
-- purpose (the V61/V73 reasoning verbatim): the b-2 export and the b-3
-- purge both scan by author THROUGH Hibernate's soft-delete filter
-- (native SQL), so a partial index would hide exactly the rows those
-- seams exist to see.
--
-- The RSVP unique index is the one-seat-per-member guard (the V73
-- uq_post_reactions_post_member precedent verbatim: the service's
-- explicit 409 comes first, the constraint is the backstop — 23505 ->
-- 409 — and a removed RSVP frees the seat for a fresh one). Its
-- event_id-leading prefix serves the grouped attending count over the
-- page's event ids (the V73 one-shape-one-index-both-reads reasoning).
-- The capacity check itself serializes on the EVENT row through the
-- service's PESSIMISTIC_WRITE read (the ListingViewsDailyRepository
-- precedent — concurrent seats on one event queue at the row, so
-- count-then-insert cannot race past capacity).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_events (
    id              UUID PRIMARY KEY,
    author_id       UUID NOT NULL,
    location_id     UUID NOT NULL,
    category        VARCHAR(20) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT NOT NULL,
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ,
    location_label  VARCHAR(200) NOT NULL,
    organizer_label VARCHAR(200) NOT NULL,
    capacity        INTEGER,
    registration    VARCHAR(30) NOT NULL,
    featured        BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_neighborhood_events_category
        CHECK (category IN ('SPORTS_FAMILY', 'VOLUNTEER', 'SOCIAL', 'MARKET', 'WORKSHOP')) NOT VALID,
    CONSTRAINT chk_neighborhood_events_registration
        CHECK (registration IN ('OPEN', 'LIMITED_SEATS', 'TABLE_RESERVATION')) NOT VALID,
    CONSTRAINT chk_neighborhood_events_time_order
        CHECK (ends_at IS NULL OR ends_at > starts_at) NOT VALID,
    CONSTRAINT chk_neighborhood_events_registration_capacity
        CHECK ((registration = 'OPEN' AND capacity IS NULL)
            OR (registration IN ('LIMITED_SEATS', 'TABLE_RESERVATION')
                AND capacity IS NOT NULL AND capacity > 0)) NOT VALID
);

ALTER TABLE neighborhood_events VALIDATE CONSTRAINT chk_neighborhood_events_category;
ALTER TABLE neighborhood_events VALIDATE CONSTRAINT chk_neighborhood_events_registration;
ALTER TABLE neighborhood_events VALIDATE CONSTRAINT chk_neighborhood_events_time_order;
ALTER TABLE neighborhood_events VALIDATE CONSTRAINT chk_neighborhood_events_registration_capacity;

-- The board query's own index (D-N5): location-scoped, live-only, on
-- the complete sort key — starts_at ASC, id ASC — so a page boundary is
-- stable even when two events start in the same second.
CREATE INDEX idx_neighborhood_events_board
    ON neighborhood_events (location_id, starts_at ASC, id ASC)
    WHERE is_deleted = FALSE;

-- The author seam (b-2 export / b-3 purge) rides a NON-partial index:
-- native SQL scans past Hibernate's soft-delete filter by design (the
-- V61 idx_neighborhood_posts_author / V73 idx_post_reactions_member
-- reasoning verbatim — a member's events are their personal data until
-- the retention window closes).
CREATE INDEX idx_neighborhood_events_author
    ON neighborhood_events (author_id, created_at, id);

CREATE TABLE event_rsvps (
    id         UUID PRIMARY KEY,
    event_id   UUID NOT NULL REFERENCES neighborhood_events(id),
    member_id  UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The product's own one seat per member per event: one LIVE RSVP per
-- member per event — the explicit 409 first, this constraint the
-- backstop (the V64/V73 precedent verbatim), and a removed
-- (soft-deleted) RSVP frees the seat for a fresh one.
CREATE UNIQUE INDEX uq_event_rsvps_event_member
    ON event_rsvps (event_id, member_id)
    WHERE is_deleted = FALSE;

-- The member seam (b-2 export / b-3 purge) rides a NON-partial index
-- (the V73 idx_post_reactions_member reasoning verbatim).
CREATE INDEX idx_event_rsvps_member
    ON event_rsvps (member_id, created_at, id);

-- Envers audit history (V24 convention) — every event's lifecycle and
-- every seat taken or freed is a revision; the export surface reads it.
CREATE TABLE neighborhood_events_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    author_id       UUID,
    location_id     UUID,
    category        VARCHAR(20),
    title           VARCHAR(200),
    description     TEXT,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    location_label  VARCHAR(200),
    organizer_label VARCHAR(200),
    capacity        INTEGER,
    registration    VARCHAR(30),
    featured        BOOLEAN,
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE event_rsvps_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    event_id   UUID,
    member_id  UUID,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
