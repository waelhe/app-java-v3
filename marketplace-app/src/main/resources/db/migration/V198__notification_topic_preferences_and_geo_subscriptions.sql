-- Phase 7 (execution plan §10 / §8.1 — notification routing): the
-- preference model's hierarchical topic dimension and the optional
-- geographic subscription — the two seats the routing contract
-- (NotificationRoutingPolicy/RoutingDecision in marketplace-notifications)
-- consults beyond the standing per-(type, channel) matrix of V40.
--
-- (a) notification_topic_preferences — the hierarchical TOPIC layer:
--     one (user, topic, channel) switch governing a whole family of
--     notification types (BOOKINGS/ORDERS/…). Resolution order in the
--     routing engine is explicit and single: the type-level row (V40
--     notification_preferences) overrides the topic-level row overrides
--     the enabled default — a hierarchy of THREE levels with two
--     override layers, never a per-combination enum (the §8.1 rule: no
--     giant section×topic×geo×channel enum — the dimensions stay
--     separate records/tables and compose at read time).
--     Channels are EMAIL and WS ONLY: the in-app (DB) channel is always
--     on (the L22 standing rule the V40 CHECK enforces) and PUSH has no
--     stored preference until the provider decision (D-10) lands — a
--     CHECK makes both facts absolute at the schema, so no row can
--     express a state the delivery path does not honor (the V40
--     DB-always-on discipline verbatim).
-- (b) notification_geo_subscriptions — the OPTIONAL geographic
--     subscription (§8.1: "اشتراكات جغرافية اختيارية بافتراضات موثقة").
--     THE DOCUMENTED ASSUMPTIONS (the plan's own requirement to
--     document them):
--       * OPT-IN ONLY — a subscription exists because the user created
--         it; there is no inferred scope and no default row.
--       * THE DEFAULT GEO SCOPE IS THE USER'S CURRENT MEMBERSHIP — with
--         no subscription rows, the routing engine's effective scope is
--         exactly the caller's ACTIVE community-membership neighborhood
--         (the CommunityMembershipPort answer), never a widened one
--         (AC-02-02: geographic expansion is an explicit user act).
--       * WITHDRAWAL IS EXPLICIT — the row is soft-deleted (the
--         BaseEntity is_deleted flag); the partial unique index keys on
--         live rows only, so a withdrawn (user, location) pair can be
--         re-subscribed later — the V60 membership precedent.
--     location_id is a plain UUID with NO FK across module boundaries
--     (the V32/V48/V54/V178 discipline — geo_locations belongs to the
--     geo module); the subscription write validates existence through
--     GeoLookupPort (the realestate L31 gate verbatim).
--     The subscriptions table is NOT an admin surface: reads and writes
--     are self-scoped (the /me seam), the recipient-isolation rule of
--     the Phase 7 gate ("مستلم لا يصل لتفضيلات غيره").
--
-- House shapes, all verbatim: full BaseEntity columns from day one
-- (V25/V32 lesson); CHECKs NOT VALID + inline VALIDATE (the V44 locking
-- shape — the tables are brand-new and empty, the V52/V58/V61/V73/V179
-- born-empty freedom); Envers mirrors in the V24 convention (all
-- columns nullable — a DEL revision row carries only (id, rev,
-- revtype)); user_id a plain UUID column with no FK to users (the
-- V18/V32/V40 module-decoupling convention).

-- ---------------------------------------------------------------------------
-- (a) the hierarchical topic preference layer
-- ---------------------------------------------------------------------------

CREATE TABLE notification_topic_preferences (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    topic      VARCHAR(30) NOT NULL,
    channel    VARCHAR(20) NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_topic_preferences_user_topic_channel
        UNIQUE (user_id, topic, channel)
);

-- The channel vocabulary's membership guard, in the V44/V179 locking
-- shape verbatim (the CI-measured lesson: PostgreSQL's grammar carries
-- NOT VALID on ALTER TABLE ADD CONSTRAINT only — an inline CHECK in
-- CREATE TABLE cannot say it, and the born-empty freedom never justified
-- an invalid statement).
ALTER TABLE notification_topic_preferences ADD CONSTRAINT notification_topic_preferences_channel_check
    CHECK (channel IN ('EMAIL', 'WS')) NOT VALID;
ALTER TABLE notification_topic_preferences VALIDATE CONSTRAINT notification_topic_preferences_channel_check;

ALTER TABLE notification_topic_preferences
    ADD CONSTRAINT notification_topic_preferences_topic_check
    CHECK (topic IN ('BOOKINGS', 'ORDERS', 'PAYMENTS', 'MESSAGING', 'COMMUNITY',
                     'NEIGHBORHOOD', 'SEARCH', 'TRUST', 'DISPUTES', 'OFFICIAL'))
    NOT VALID;

ALTER TABLE notification_topic_preferences VALIDATE CONSTRAINT notification_topic_preferences_topic_check;

-- The routing engine's per-user resolution read: every stored switch of
-- one user in one scan (the sparse-override lookup of the V40 matrix).
CREATE INDEX idx_notification_topic_preferences_user
    ON notification_topic_preferences (user_id);

-- The caller's own list (created_at DESC, id DESC — the D-N5 complete
-- ordering key), live-only.
CREATE INDEX idx_notification_topic_preferences_user_recent
    ON notification_topic_preferences (user_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- Envers mirror (V24 convention — all columns nullable).
CREATE TABLE notification_topic_preferences_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    user_id    UUID,
    topic      VARCHAR(30),
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

-- ---------------------------------------------------------------------------
-- (b) the optional geographic subscription (opt-in; default = membership)
-- ---------------------------------------------------------------------------

CREATE TABLE notification_geo_subscriptions (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    location_id UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One LIVE subscription per (user, location) ever — the V60 membership
-- precedent: partial on the live rows only, so a withdrawn pair frees
-- its seat for a future re-subscription (the explicit withdrawal
-- contract; a hard UNIQUE would bury the pair forever).
CREATE UNIQUE INDEX uq_notification_geo_subscriptions_live
    ON notification_geo_subscriptions (user_id, location_id)
    WHERE is_deleted = FALSE;

-- The urgent-alert fan-out's own scan: every LIVE subscriber of one
-- location in one index-only pass (the routing engine unions this with
-- the members port's answer — one level-3 scope, two data owners).
CREATE INDEX idx_notification_geo_subscriptions_location_live
    ON notification_geo_subscriptions (location_id)
    WHERE is_deleted = FALSE;

-- The caller's own list in the D-N5 order, live-only.
CREATE INDEX idx_notification_geo_subscriptions_user_recent
    ON notification_geo_subscriptions (user_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- Envers mirror (V24 convention — all columns nullable).
CREATE TABLE notification_geo_subscriptions_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    user_id     UUID,
    location_id UUID,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
