-- JT-20 (the discovery waves D1-D4 — AC-20-05: «المتابعة» لا تُستبدل
-- بـ"لك" — the follow choice is the member's own, never an algorithmic
-- substitute): the generalized follow — one member's explicit follow of
-- one followable source, USER or GROUP, the row behind the
-- FOLLOWED_SOURCES rail's relationship leg and the /me/follows
-- management surface.
--
-- THE ONE-HOME PHILOSOPHY ("البيت الواحد" — the FollowedSourcesPort
-- contract verbatim): each follow TYPE has exactly one home table and
-- one write path —
--   PROVIDER → provider_follows (V93 — untouched: no row migration, no
--     dual write; its V93 endpoints keep answering exactly as they do
--     today);
--   USER/GROUP → THIS table (the discovery wave's generalized store).
-- The identity module's FollowedSourcesPort adapter UNIONS the two homes
-- at READ time (two live queries, one honest set) — the rail reads one
-- union, the old surface keeps its home, and no data ever moves. One
-- home per type is what makes that union truthful: a follow written
-- through its own house surface is discoverable through the rail the
-- moment the adapter ships, with zero backfill.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/media_assets, V52/listing_leads, V54/saved_searches, V93
-- discipline): user_id lives in the users.id space (identity's own
-- neighborhood — the write path checks it directly through the module's
-- local UserRepository); followable_id lives in the TYPE'S OWN id space
-- (USER → users.id, GROUP → neighborhood_groups.id — existence checked
-- through the shared GroupLookupPort seam, community's own adapter).
--
-- THE CLOSED VOCABULARY — the D-N7 two-sided discipline: the Java enum
-- FollowableType (USER/GROUP) is the single source of truth, this
-- migration's CHECK is the SQL-side guard.
--
-- THE CHECK'S NOT VALID → VALIDATE PAIR, ONE FILE (the V66/V75 lesson's
-- documented exception): the house splits the pair across two migrations
-- when the constraint lands on a LIVE table — inside one transaction the
-- ALTER's ACCESS EXCLUSIVE lock is held through the validation scan and
-- blocks that table's live reads/writes (the measured V66/V75 lesson;
-- V93's ninth notification type rode V94 for exactly that reason). A
-- brand-new table has no live traffic to block: CREATE + ADD NOT VALID +
-- VALIDATE commit together, the scan sees ZERO rows, and no other
-- session can even see the table before this migration's transaction
-- closes — the VALIDATE here costs nothing and carries no lock hazard.
-- The explicit two-step form (not a bare inline CHECK) is kept anyway so
-- the pair stays visible and the house's two-step vocabulary is
-- mirrored; the exception — the empty-table case — is written down here.
--
-- Every BaseEntity column present from day one (the V25/V32 lesson);
-- the Envers mirror follows the V24 convention (the V33 lesson: a base
-- table's @Audited twin missing breaks audit INSERTs silently).
--
-- Indexes (the V93 shape, one per measured read):
-- (a) uq_follows_one_live — one LIVE follow per (member, type, source);
--     the service's idempotent read comes first (a replay answers the
--     standing row, never a 409 — the generalized surface's own
--     idempotent contract), this partial unique index is the
--     concurrent-insert backstop; the soft delete frees the triple, so
--     an unfollow then a re-follow is legal by construction (the
--     uq_provider_follows_once / uq_review_votes_review_voter twin).
-- (b) idx_follows_source_scan — the source-side read (who follows this
--     USER / GROUP), live-only (the idx_provider_follows_provider_scan
--     twin — the rail's and the future fan-out reads land here).
-- (c) idx_follows_user_recent — the member's own list in the L32
--     deterministic order (the full ordering key, no wobbly pages; the
--     idx_provider_follows_user_recent twin).
--
-- Checksum registration: the discovery wave registers its V-numbers in
-- migration-checksums.properties in the wave's integrative union pass —
-- ONE deterministic edit of the shared manifest instead of parallel
-- edits colliding on the same file (the #529 union precedent).
-- MigrationChecksumGuardTest flags V177 as unregistered until that pass
-- (the documented pending item, the wave worklog's own row).

CREATE TABLE follows (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    followable_type VARCHAR(20) NOT NULL,
    followable_id   UUID NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The closed USER/GROUP vocabulary (see the header: NOT VALID + explicit
-- VALIDATE in the same file is the documented empty-table exception).
ALTER TABLE follows
    ADD CONSTRAINT follows_followable_type_check
    CHECK (followable_type IN ('USER', 'GROUP')) NOT VALID;

-- The table is born empty (zero rows, zero live traffic): the VALIDATE
-- scan costs nothing and the pair is complete from the first live row.
ALTER TABLE follows
    VALIDATE CONSTRAINT follows_followable_type_check;

-- One LIVE follow per (member, type, source) — the service's idempotent
-- read first, this partial unique index the concurrent-insert backstop;
-- the soft delete frees the triple for a legitimate re-follow.
CREATE UNIQUE INDEX uq_follows_one_live
    ON follows (user_id, followable_type, followable_id) WHERE is_deleted = FALSE;

-- The source-side scan (who follows this USER / GROUP), live-only.
CREATE INDEX idx_follows_source_scan
    ON follows (followable_type, followable_id) WHERE is_deleted = FALSE;

-- The member's own list in the L32 deterministic order.
CREATE INDEX idx_follows_user_recent
    ON follows (user_id, created_at DESC, id DESC) WHERE is_deleted = FALSE;

-- Envers mirror (V24 convention) — all columns NULLABLE exactly as V24
-- made them: a DEL revision row carries only (id, rev, revtype); NOT NULL
-- on a DEL-revision column makes every soft delete a 500.
CREATE TABLE follows_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    user_id         UUID,
    followable_type VARCHAR(20),
    followable_id   UUID,
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
