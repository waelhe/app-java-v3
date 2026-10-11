-- D-3 (discovery wave 3 — JT-19/D-30, the «أخبار محلية» row): the local
-- news surface — items published by VERIFIED news publishers, separate
-- from the official-messages channel (an official announcement is the
-- platform speaking; a news item is a NAMED OUTSIDE PUBLISHER speaking
-- through us, so the attribution is the product: the publisher, the
-- source, the original date and the original link ride every read —
-- AC-20-09) and from the urgent-alert family (D-3's other lever, whose
-- own delegated-source gate this module's VERIFIED-publisher requirement
-- mirrors).
--
-- The honesty contract (JT-19/D-30, AC-20-09/AC-20-10 — the knowledge
-- module's revise/withdraw semantics are the governing template):
--   * a CORRECTION never mutes the original — the item stays displayed,
--     honestly marked (is_corrected/corrected_at/correction_note), and
--     the note is REQUIRED (a silent edit is the one thing this surface
--     must never do). The original publication date is immutable — the
--     correction edits the content, never the history.
--   * a WITHDRAWAL is a DOMAIN flag (is_withdrawn/withdrawn_at), NOT the
--     infrastructure soft delete: the row keeps its Envers trail and the
--     public reads stop returning it the moment the flag lands (the
--     knowledge withdraw's display semantics, without reclaiming the
--     @SoftDelete seam the BaseEntity owns). Unknown and withdrawn read
--     identically to the public: 404.
--   * the reflection is IMMEDIATE — both markers ride the same
--     transaction as the write; there is no staged publication state.
--
-- The write gate (documented product decision): CREATING a news item
-- requires a VERIFIED publisher — the same delegated-source condition
-- the urgent-alert wave rides (JT-17's authorized-source ruling); an
-- UNVERIFIED/PENDING/REJECTED publisher answers 409 before any write.
-- The publisher's lifecycle is ADMINISTRATIVE (the admin registers the
-- publisher and is the verdict's only mover) — PENDING exists in the
-- vocabulary for the future self-service registration flow (the V154
-- widening discipline: the enum is the contract, a future state rides a
-- migration, not a rebuild).
--
-- NO EVENTS, BY DECISION: news is a public DISPLAY surface only — it is
-- not AI-indexed and publishes no integration facts today. News indexing
-- in AI follows the knowledge module's events pattern LATER, by an
-- explicit decision (the pair-of-events upsert/drop shape the
-- KnowledgeEntryPublishedEvent/KnowledgeEntryWithdrawnEvent established
-- is the ready-made template when that decision lands).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECKs in the V44 locking shape: NOT VALID + inline VALIDATE —
--     the tables are brand-new and empty (the V52/V58/V61/V73
--     born-empty freedom).
--   * Plain UUID columns for the cross-module seams — location_id (the
--     geo tree node) carries NO relation across module boundaries (the
--     V32/V48/V54 discipline). The OPTIONAL scope is level-3-gated by
--     the service through GeoLookupPort when present (the D-N2
--     hierarchy), NULL = the whole-city board.
--   * The publisher_id FK is the sanctioned INTERNAL reference (the V83
--     event_rsvps.event_id precedent verbatim): a plain UUID column in
--     Java, a REAL FK in SQL — the item and its publisher are one
--     aggregate inside this module's own boundary.
--   * The _aud mirrors in the V24 shape: all columns nullable.
--
-- Indexes: the board read is the (published_at DESC, id DESC) complete
-- sort key (D-N5: no shaky pages — two items published in the same
-- second keep a stable boundary), live-only partial on the read's own
-- double flag; the publisher index serves the per-publisher scan (the
-- publisher's own ledger read) on the same live-only shape.
--
-- Numbering: V179 — Track B's range (V150-V189).
--
-- Checksum: deliberately NOT registered here — this wave's union task
-- owns migration-checksums.properties (the deterministic-union pattern
-- the multi-branch waves established); MigrationChecksumGuardTest's
-- rule 2 stays lit for V179 until that registration lands.

CREATE TABLE news_publishers (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    website_url         VARCHAR(500),
    verification_state  VARCHAR(30) NOT NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The stored vocabulary's membership guard (the enum mirror — the V154
-- quadruple verbatim; a future value widens by migration, never by
-- rebuilding the type).
ALTER TABLE news_publishers ADD CONSTRAINT chk_news_publishers_verification_state
    CHECK (verification_state IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED')) NOT VALID;
ALTER TABLE news_publishers VALIDATE CONSTRAINT chk_news_publishers_verification_state;

CREATE TABLE news_items (
    id               UUID PRIMARY KEY,
    publisher_id     UUID NOT NULL REFERENCES news_publishers(id),
    title            VARCHAR(200) NOT NULL,
    summary          TEXT,
    source_url       VARCHAR(1000) NOT NULL,
    published_at     TIMESTAMPTZ NOT NULL,
    location_id      UUID,
    is_corrected     BOOLEAN NOT NULL DEFAULT FALSE,
    corrected_at     TIMESTAMPTZ,
    correction_note  VARCHAR(1000),
    is_withdrawn     BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawn_at     TIMESTAMPTZ,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The honesty pair's own integrity backstops (D-N7's shape — the
-- service validates the same rules before any write; these CHECKs are
-- the concurrent-insert backstop): a corrected item MUST carry its
-- correction timestamp and note (the note is the correction's whole
-- point), and a withdrawn item MUST carry its withdrawal timestamp.
ALTER TABLE news_items ADD CONSTRAINT chk_news_items_correction_pair
    CHECK (is_corrected = FALSE OR (corrected_at IS NOT NULL AND correction_note IS NOT NULL)) NOT VALID;
ALTER TABLE news_items VALIDATE CONSTRAINT chk_news_items_correction_pair;

ALTER TABLE news_items ADD CONSTRAINT chk_news_items_withdrawal_pair
    CHECK (is_withdrawn = FALSE OR withdrawn_at IS NOT NULL) NOT VALID;
ALTER TABLE news_items VALIDATE CONSTRAINT chk_news_items_withdrawal_pair;

-- The board read's own index (D-N5): the complete (published_at, id)
-- sort key, live-only on both flags — the exact shape the public board
-- query carries (is_withdrawn = FALSE from the read, is_deleted = FALSE
-- from Hibernate's soft-delete filter, which implies this predicate).
CREATE INDEX idx_news_items_board
    ON news_items (published_at DESC, id DESC)
    WHERE is_withdrawn = FALSE AND is_deleted = FALSE;

-- The publisher's own ledger scan (publisher-leading prefix, the same
-- live-only shape).
CREATE INDEX idx_news_items_publisher
    ON news_items (publisher_id, published_at DESC, id DESC)
    WHERE is_withdrawn = FALSE AND is_deleted = FALSE;

-- Envers audit history (V24 convention): every registration (ADD), every
-- verification move (MOD), every publication/correction/withdrawal
-- (MOD) and the soft delete (DEL) leave a revision. All columns NULLABLE
-- exactly as V24 made them: a DEL revision row carries only
-- (id, rev, revtype); NOT NULL on a DEL-revision column makes every soft
-- delete a 500 (the V93 lesson).
CREATE TABLE news_publishers_aud (
    id                  UUID NOT NULL,
    rev                 INTEGER NOT NULL,
    revtype             SMALLINT,
    name                VARCHAR(200),
    website_url         VARCHAR(500),
    verification_state  VARCHAR(30),
    is_deleted          BOOLEAN,
    version             BIGINT,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ,
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE news_items_aud (
    id               UUID NOT NULL,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT,
    publisher_id     UUID,
    title            VARCHAR(200),
    summary          TEXT,
    source_url       VARCHAR(1000),
    published_at     TIMESTAMPTZ,
    location_id      UUID,
    is_corrected     BOOLEAN,
    corrected_at     TIMESTAMPTZ,
    correction_note  VARCHAR(1000),
    is_withdrawn     BOOLEAN,
    withdrawn_at     TIMESTAMPTZ,
    is_deleted       BOOLEAN,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
