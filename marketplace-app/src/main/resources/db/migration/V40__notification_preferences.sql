-- L22 (feature-expansion roadmap §5, Week 2): notification preferences —
-- the per user x notification type x channel (DB/EMAIL/WS) unsubscribe
-- switches behind GET/PUT /api/v1/notifications/preferences.
--
-- Version note: the roadmap's L22 section says "migration V39", but that
-- number is now taken by the open codex-sweep PR #253 branch
-- (V39__repair_provider_user_id_backfill.sql). Two files with version 39
-- would break Flyway's applied-migration validation whichever PR merges
-- first, so L22 ships as V40 — the next free number on the chain.
--
-- Sparse-override semantics: only explicit overrides are stored. The
-- absence of a row IS the default, and the default is enabled — so with
-- an empty table the system behaves exactly as it did pre-L22 (roadmap
-- acceptance criterion 2: "the default is precisely the current
-- behavior"). "Back to default" is enabled=true; there is no delete path.
--
-- Every BaseEntity column present from day one (the V25 lesson); the
-- Envers audit table follows the V24 convention (_aud + rev, REVTYPE
-- 0/1/2) so every @Audited switch change leaves its revision (roadmap
-- acceptance criterion 3).
--
-- user_id is a plain UUID column without FK to users — the same module
-- decoupling as notifications.recipient_id (V18) and media_assets (V32):
-- the notifications module resolves users through UserLookupPort, never
-- through a database-level dependency on the identity module's table.

CREATE TABLE notification_preferences (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    type       VARCHAR(100) NOT NULL CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE')),
    channel    VARCHAR(20) NOT NULL CHECK (channel IN ('DB', 'EMAIL', 'WS')),
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_preferences_user_type_channel UNIQUE (user_id, type, channel),
    -- The in-app channel is always on (roadmap L22: "inside the app
    -- always"): the API rejects a DB opt-out, and this CHECK makes the
    -- invariant absolute — even raw SQL cannot store a state the delivery
    -- path does not honor.
    CONSTRAINT ck_notification_preferences_db_always_on CHECK (channel <> 'DB' OR enabled)
);

-- Envers audit history (V24 convention): every switch change (ADD/MOD)
-- leaves a revision carrying the full row snapshot.
CREATE TABLE notification_preferences_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    user_id    UUID,
    type       VARCHAR(100),
    channel    VARCHAR(20),
    enabled    BOOLEAN,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
