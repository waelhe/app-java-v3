-- B-13 (compliance plan C.3 — the owner's 2026-10-07 ruling, recorded
-- verbatim: «المؤسسات فيها جزء من المجتمع»): the institution registry
-- (سجل الجهة) — the institution-specific EDGES in the new module. The
-- MEMBERSHIP machinery stays in community (the ruling's own division:
-- «بلا تكرار أي آلية عضوية خارج community» — the membership
-- generalization rides the existing neighborhood_memberships machine
-- via the CR protocol, never a copy here).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECKs in the V44 locking shape: NOT VALID + inline VALIDATE —
--     the table is brand-new and empty (the V52/V58 precedent).
--   * The _aud mirror in the V24 shape: all columns nullable.
--   * Plain UUID columns for the cross-module seams — representative_id
--     (users.id space, the A1 convention) and location_id (the geo tree
--     node) carry NO JPA/DB relation across module boundaries (the
--     V32/V48/V54 discipline — neighborhood_memberships' own shape).
--
-- Numbering: V154 — Track B's range (V150-V189).

CREATE TABLE institutions (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    type                VARCHAR(20) NOT NULL,
    representative_id   UUID NOT NULL,
    location_id         UUID NOT NULL,
    address             VARCHAR(300),
    phone               VARCHAR(20),
    website             VARCHAR(300),
    description         TEXT,
    verification_state  VARCHAR(20) NOT NULL,
    registered_at       TIMESTAMPTZ NOT NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The stored vocabularies' membership guards (the enum mirrors — a future
-- value widens by migration, never by rebuilding the type).
ALTER TABLE institutions ADD CONSTRAINT chk_institutions_type
    CHECK (type IN ('SCHOOL','UNIVERSITY','CLINIC','MOSQUE','CHARITY','GOVERNMENT','COMPANY','NGO')) NOT VALID;
ALTER TABLE institutions VALIDATE CONSTRAINT chk_institutions_type;

ALTER TABLE institutions ADD CONSTRAINT chk_institutions_verification_state
    CHECK (verification_state IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED')) NOT VALID;
ALTER TABLE institutions VALIDATE CONSTRAINT chk_institutions_verification_state;

-- The public board's read: newest-registered first with the complete
-- (created_at, id) key keeping the page boundary stable (D-N5).
CREATE INDEX idx_institutions_board
    ON institutions (created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The representative's own registry list.
CREATE INDEX idx_institutions_representative
    ON institutions (representative_id, registered_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The review queue's drain order: the state's own clock, oldest pending
-- claim first (the NeighborhoodVerificationAdminController's own read —
-- the partial index carries the state axis the queue filters by).
CREATE INDEX idx_institutions_verification_queue
    ON institutions (verification_state, updated_at ASC, id ASC)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every registration (ADD), every
-- verification move (MOD) and the soft delete (DEL) leave a revision.
CREATE TABLE institutions_aud (
    id                  UUID NOT NULL,
    rev                 INTEGER NOT NULL,
    revtype             SMALLINT,
    name                VARCHAR(200),
    type                VARCHAR(20),
    representative_id   UUID,
    location_id         UUID,
    address             VARCHAR(300),
    phone               VARCHAR(20),
    website             VARCHAR(300),
    description         TEXT,
    verification_state  VARCHAR(20),
    registered_at       TIMESTAMPTZ,
    is_deleted          BOOLEAN,
    version             BIGINT,
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ,
    updated_by          VARCHAR(200),
    updated_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
