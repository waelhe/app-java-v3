-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
-- staff notification recipient registry — the Saleor Users-domain rows
-- behind staffNotificationRecipientCreate/Update/Delete. The registry is
-- the routing table for staff-targeted operational mail (order events);
-- this slice owns the registry CRUD with full audit; the order-event
-- consumer joins later when the orders module exposes its event surface.
--
-- Saleor's model is oneof-shaped: a recipient is either a staff user
-- reference (the user's own email + active state at send time) or a bare
-- email address. Both columns are nullable and the CHECK requires exactly
-- one of them to carry a value (the wire validates the pair, the store
-- keeps the invariant).
--
-- House shapes: the V96 attribution rules (full BaseEntity columns,
-- same-script CHECK validation, partial unique indexes over live rows,
-- V24-shaped _aud mirrors).

CREATE TABLE staff_notification_recipients (
    id          UUID PRIMARY KEY,
    user_id     UUID REFERENCES users (id),
    email       VARCHAR(320),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_notification_recipients_oneof_chk
        CHECK ((user_id IS NULL) <> (email IS NULL)),
    CONSTRAINT staff_notification_recipients_email_shape_chk
        CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$')
);

-- One live registry row per bare email (the dedup key the update/delete
-- by-email journey relies on); user-referenced rows are unique per user.
CREATE UNIQUE INDEX staff_notification_recipients_email_live_uq
    ON staff_notification_recipients (email) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX staff_notification_recipients_user_live_uq
    ON staff_notification_recipients (user_id) WHERE is_deleted = FALSE AND user_id IS NOT NULL;

CREATE TABLE staff_notification_recipients_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    user_id     UUID,
    email       VARCHAR(320),
    active      BOOLEAN,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);
