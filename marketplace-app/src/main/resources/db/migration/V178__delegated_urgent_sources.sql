-- D-3/D-4 (the discovery waves — the delegated urgent alert, CMP-46/JT-10):
-- the institutions module's official-alert layer — a DELEGATED source
-- (بلدية، دفاع مدني، مرافق، سلطة صحية، سلطة تعليمية) publishes a
-- neighborhood-scoped alert with an explicit validity window, and every
-- surface (the discovery rail, the notification consumers — task 5-f)
-- reads the same honest state. The authority is the SOURCE's own
-- delegation, never a popularity signal and never a community or AI tag
-- (AC-20-06: an urgent tag does not make community content official —
-- CMP-46's own rule: الاستعجال نص يُعرض، لا إشارة شعبية).
--
-- The two tables are ONE aggregate inside the module's own boundary:
--   * delegated_urgent_sources — the delegating official body, on the
--     V154 institutions registry's own verification quartet VERBATIM
--     ('UNVERIFIED','PENDING','VERIFIED','REJECTED' — the V97 quartet
--     too: one trust vocabulary for the whole platform). Born UNVERIFIED,
--     VERIFIED only by the same administrative gate that verifies
--     institutions (the admin's verdict is the delegation).
--   * urgent_alerts — one source's alert: scoped to exactly one geo tree
--     node (level 3 — resolved and LEVEL-CHECKED through GeoLookupPort
--     before any write, the D-N2 discipline), carrying an explicit
--     validity window (JT-10's سريان) and its own withdrawal facts.
--
-- The withdrawal philosophy (JT-10's «تصحيح/سحب ينعكس على كل الأسطح»):
-- the alert row keeps its audit trail (an Envers mirror below) and the
-- withdrawal rides TWO explicit columns (is_withdrawn, withdrawn_at) —
-- NOT the house soft delete. is_deleted stays the row-lifecycle leg (the
-- b-2/b-3 retention seams), while is_withdrawn is the DOMAIN's honesty
-- leg: the UrgentAlertsPort adapter answers a withdrawn alert with
-- silence (the surfaces stop serving it immediately) while the
-- notifications ledger keeps the delivery facts (a sent notification is
-- a delivery fact). The UrgentAlertWithdrawnEvent carries the same
-- decision to the Modulith consumers.
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECKs in the V44 locking shape: NOT VALID + inline VALIDATE —
--     the tables are brand-new and empty in this transaction (the
--     V52/V58/V61/V73 same-transaction freedom; zero rows to scan).
--   * source_id is the sanctioned INTERNAL reference (the V7
--     messages.conversation_id / V83 event_rsvps.event_id precedent): a
--     plain UUID column in Java, a real FK in SQL — the alert and its
--     source are one aggregate inside the module.
--   * location_id stays a plain UUID column with NO FK (the
--     V32/V48/V54/V83 discipline — cross-module seams stay relation-free;
--     the geo node is validated through GeoLookupPort, never through a
--     database-level dependency).
--   * The _aud mirrors in the V24 shape: all columns nullable (the V33
--     lesson: base-table columns without the _aud twin break audit
--     INSERTs silently).
--
-- Numbering: V178 — Track B's range (V150-V189), the D-3/D-4 wave slot.
-- Registered in migration-checksums.properties by the wave's integration
-- commit (the sibling V-slots of the same wave land out-of-order —
-- out-of-order: true, the documented multi-branch discipline).
-- Checksum NOT registered here by the task's explicit mandate.

CREATE TABLE delegated_urgent_sources (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    source_type        VARCHAR(40) NOT NULL,
    verification_state VARCHAR(30) NOT NULL,
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_delegated_urgent_sources_type
        CHECK (source_type IN ('MUNICIPALITY', 'CIVIL_DEFENSE', 'UTILITIES',
                               'HEALTH_AUTHORITY', 'EDUCATION_AUTHORITY', 'OTHER_DELEGATED')) NOT VALID,
    CONSTRAINT chk_delegated_urgent_sources_verification_state
        CHECK (verification_state IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED')) NOT VALID
);

ALTER TABLE delegated_urgent_sources VALIDATE CONSTRAINT chk_delegated_urgent_sources_type;
ALTER TABLE delegated_urgent_sources VALIDATE CONSTRAINT chk_delegated_urgent_sources_verification_state;

-- The delegation review queue's drain order: the state's own clock,
-- oldest pending claim first (the V154 institutions queue index shape
-- verbatim — the admin's one review surface for the wave's sources).
CREATE INDEX idx_delegated_urgent_sources_queue
    ON delegated_urgent_sources (verification_state, updated_at ASC, id ASC)
    WHERE is_deleted = FALSE;

CREATE TABLE urgent_alerts (
    id            UUID PRIMARY KEY,
    source_id     UUID NOT NULL REFERENCES delegated_urgent_sources(id),
    location_id   UUID NOT NULL,
    level         VARCHAR(20) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    body          TEXT NOT NULL,
    valid_from    TIMESTAMPTZ NOT NULL,
    valid_until   TIMESTAMPTZ,
    is_withdrawn  BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawn_at  TIMESTAMPTZ,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_urgent_alerts_level
        CHECK (level IN ('CRITICAL', 'SEVERE', 'ADVISORY')) NOT VALID,
    CONSTRAINT chk_urgent_alerts_window
        CHECK (valid_until IS NULL OR valid_until > valid_from) NOT VALID
);

ALTER TABLE urgent_alerts VALIDATE CONSTRAINT chk_urgent_alerts_level;
ALTER TABLE urgent_alerts VALIDATE CONSTRAINT chk_urgent_alerts_window;

-- The public read's own index (the service's one active-alerts query):
-- location-scoped, live-only (withdrawn alerts never serve — JT-10), on
-- the complete sort key (valid_from DESC, id DESC — D-N5's complete-key
-- discipline: the freshest alert first, page boundaries stable). A plain
-- CREATE INDEX inside this transaction: the table is born empty (the
-- V61/V73 same-transaction freedom; CONCURRENTLY is for populated ones).
CREATE INDEX idx_urgent_alerts_active
    ON urgent_alerts (location_id, valid_from DESC, id DESC)
    WHERE is_withdrawn = FALSE;

-- Envers audit history (V24 convention): every delegation verdict (MOD),
-- every publish (ADD), every withdrawal (MOD) and the row-lifecycle
-- delete (DEL) leave a revision — the CJIS-style honesty leg keeps its
-- own trail.
CREATE TABLE delegated_urgent_sources_aud (
    id                 UUID NOT NULL,
    rev                INTEGER NOT NULL,
    revtype            SMALLINT,
    name               VARCHAR(200),
    source_type        VARCHAR(40),
    verification_state VARCHAR(30),
    is_deleted         BOOLEAN,
    version            BIGINT,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE urgent_alerts_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    source_id    UUID,
    location_id  UUID,
    level        VARCHAR(20),
    title        VARCHAR(200),
    body         TEXT,
    valid_from   TIMESTAMPTZ,
    valid_until  TIMESTAMPTZ,
    is_withdrawn BOOLEAN,
    withdrawn_at TIMESTAMPTZ,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
