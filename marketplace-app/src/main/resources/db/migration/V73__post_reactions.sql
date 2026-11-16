-- L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions layer):
-- the feed's reaction state. The community module's fourth state
-- migration, on the V60/V61/V64 membership+posts+reports base — a
-- reaction is one member's single live "thank" on one VISIBLE post,
-- the feed's lightest write and Nextdoor's own #1 signature.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61/V64 discipline): member_id lives in the
-- users.id space and arrives through the identity seams; post_id is the
-- plan's sanctioned INTERNAL reference (the V7 messages.conversation_id /
-- V61 post_comments.post_id precedent): a plain UUID column in Java, a
-- real FK in SQL — the post and its reactions are one aggregate inside
-- the module's own boundary.
--
-- No enumerated column rides here (the reaction IS the fact — one shape,
-- no vocabulary), so no CHECK membership guard exists to widen; the
-- one-voice-per-member guard is the partial unique index below (the
-- V64 uq_content_reports_reporter_target precedent verbatim: the
-- service's explicit 409 comes first, the constraint is the backstop,
-- and a removed reaction frees the voice for a fresh one).
--
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirror follows the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently). The
-- table is born empty in this transaction — zero rows to scan, no other
-- session can see it before the commit (the V61/V64 same-transaction
-- locking freedom).
--
-- The feed's count read is a grouped aggregate over the page's post ids
-- (SELECT post_id, count(*) ... WHERE post_id IN (...) GROUP BY post_id);
-- the unique index's post_id-leading columns serve exactly that prefix
-- scan, so no second index exists — one shape, one index, both reads.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE post_reactions (
    id         UUID PRIMARY KEY,
    post_id    UUID NOT NULL REFERENCES neighborhood_posts(id),
    member_id  UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The product's own "صوت واحد لكل عضو" (one voice per member): one LIVE
-- reaction per member per post — the explicit 409 first, this constraint
-- the backstop (23505 -> 409, the L30 G-N1 / V64 precedent verbatim), and
-- a removed (soft-deleted) reaction frees the voice for a fresh one.
CREATE UNIQUE INDEX uq_post_reactions_post_member
    ON post_reactions (post_id, member_id)
    WHERE is_deleted = FALSE;

-- The author seam (b-2 export / b-3 purge) rides a NON-partial index:
-- native SQL scans past Hibernate's soft-delete filter by design (the
-- V61 idx_neighborhood_posts_author reasoning verbatim — a member's
-- reactions are their personal data until the retention window closes).
CREATE INDEX idx_post_reactions_member
    ON post_reactions (member_id, created_at, id);

-- Envers audit history (V24 convention) — every thank and every un-thank
-- is a revision; the export surface reads it.
CREATE TABLE post_reactions_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    post_id    UUID,
    member_id  UUID,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
