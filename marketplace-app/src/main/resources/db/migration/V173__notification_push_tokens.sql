-- V173 — Stage 7 (plan D-10, ADR-0003): the push channel. Two additive
-- facts: the preferences vocabulary widens by exactly one channel member
-- (the V40 inline CHECK, auto-named by PostgreSQL, rewritten in place),
-- and the device-token registry the FCM HTTP v1 channel sends through —
-- Google's own Admin SDK (the parent POM's managed firebase-admin, the
-- house's recorded "official client" decision).

-- 1. The channel vocabulary: PUSH joins DB/EMAIL/WS (the sparse-override
--    semantics are untouched — no row is the enabled default, so every
--    existing user's behavior is unchanged until they opt explicitly).
ALTER TABLE notification_preferences
    DROP CONSTRAINT notification_preferences_channel_check;
ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_channel_check
        CHECK (channel IN ('DB', 'EMAIL', 'WS', 'PUSH'));

-- 2. The device-token registry — the push channel's addressing record.
--    user_id is a plain UUID column without FK (the V40 discipline
--    verbatim: the notifications module resolves users through its
--    lookup seams, never a database-level identity dependency). The
--    token is UNIQUE — one hardware token, one row, the register call
--    is the upsert. The Envers audit table follows the V24 convention.
CREATE TABLE push_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    token      TEXT NOT NULL UNIQUE,
    platform   VARCHAR(16) NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_push_tokens_user ON push_tokens (user_id);

-- 3. The Envers audit mirror (the V24 convention verbatim: rev is
--    INTEGER against revinfo, no FK — the house pattern).
CREATE TABLE push_tokens_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    user_id    UUID,
    token      TEXT,
    platform   VARCHAR(16),
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
