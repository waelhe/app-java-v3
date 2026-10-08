-- L45 (neighborhood community plan §5 — the moderation & reports
-- layer): the moderation queue's own state. The community module's
-- third migration, on the V60/V61 membership+posts base — a report is
-- one member's flag on one piece of authored content, the queue's unit.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61 discipline): reporter_id, resolved_by and
-- the moderated author all live in the users.id space and arrive through
-- the identity seams; target_id is the target surface's OWN id — a
-- neighborhood_posts.id for POST, a post_comments.id for COMMENT —
-- resolved and VISIBLE-gated through the service's target gate before
-- any write (the L42 visiblePost convention), which is why no FK rides
-- here either: the target's existence is the application gate's own
-- honest 404, and a target row that later soft-deletes must NOT cascade
-- anywhere (the report history stays — b-5's retention, the Envers
-- trail keeps every flip).
--
-- target_type, reason and status are DB-enumerated columns (D-N7 — every
-- enumerated column carries its DB-level membership guard): POST/COMMENT
-- today (the plan's own "تعداد قابل للامتداد" — messages and listings
-- widen it at their own gates), SPAM/HARASSMENT/INAPPROPRIATE/OTHER (the
-- plan's closed reason vocabulary), OPEN/RESOLVED/DISMISSED (the one
-- transition out of OPEN is the resolve command alone).
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately) + VALIDATE. The VALIDATE rides this same
-- migration deliberately — the L36/V56+V57 Squawk split exists for
-- EXISTING tables (a scan under the still-held ACCESS EXCLUSIVE of the
-- same transaction's ALTERs blocks live traffic); THIS table is born in
-- this transaction: zero rows to scan, and no other session can even
-- see it before the commit — the V61 precedent verbatim (its three
-- same-migration VALIDATEs ran in production twice: postgres-18 and
-- Neon, measured). Every BaseEntity column present from day one
-- (V25/V32 lesson); the Envers mirror follows the V24 convention
-- (V33 lesson: base-table columns without the _aud twin break audit
-- INSERTs silently).
--
-- The queue index is the query's own shape: status-first (the drain
-- reads OPEN by default), FIFO on the complete sort key (created_at ASC,
-- id ASC — D-N5: no shaky pages; the feed's DESC is a reader preference,
-- the queue's ASC is the operator's drain order), live rows only.
--
-- The partial unique index is the plan's own "تقرير واحد لكل
-- مرسل/هدف": one LIVE report per reporter+target — the service's
-- explicit 409 comes first, the constraint is the backstop (the 23505
-- house translation, the L30 G-N1 precedent verbatim), and a purged
-- report history never blocks a fresh report on the same target.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE content_reports (
    id              UUID PRIMARY KEY,
    reporter_id     UUID NOT NULL,
    target_type     VARCHAR(20) NOT NULL,
    target_id       UUID NOT NULL,
    reason          VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    resolution_note VARCHAR(2000),
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_content_reports_target_type
        CHECK (target_type IN ('POST', 'COMMENT')) NOT VALID,
    CONSTRAINT chk_content_reports_reason
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE', 'OTHER')) NOT VALID,
    CONSTRAINT chk_content_reports_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')) NOT VALID
);

ALTER TABLE content_reports VALIDATE CONSTRAINT chk_content_reports_target_type;
ALTER TABLE content_reports VALIDATE CONSTRAINT chk_content_reports_reason;
ALTER TABLE content_reports VALIDATE CONSTRAINT chk_content_reports_status;

-- The queue query's own index: status-first (the drain reads OPEN by
-- default), FIFO on the complete sort key, live rows only.
CREATE INDEX idx_content_reports_queue
    ON content_reports (status, created_at, id)
    WHERE is_deleted = FALSE;

-- The plan's own "تقرير واحد لكل مرسل/هدف": one LIVE report per
-- reporter+target — the explicit 409 first, this constraint the
-- backstop (23505 -> 409, the L30 G-N1 precedent verbatim).
CREATE UNIQUE INDEX uq_content_reports_reporter_target
    ON content_reports (reporter_id, target_type, target_id)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention) — every report creation and
-- every resolve flip is a revision; the moderation surface reads it
-- (the plan's criterion 6).
CREATE TABLE content_reports_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    reporter_id     UUID,
    target_type     VARCHAR(20),
    target_id       UUID,
    reason          VARCHAR(30),
    status          VARCHAR(20),
    resolution_note VARCHAR(2000),
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
