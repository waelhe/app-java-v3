-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
-- navigation slot assignment — Saleor's assignNavigation maps a menu onto
-- one of the shop's two navigation slots (MAIN/SECONDARY). The platform's
-- equivalent is this small registry: one row per slot (the partial unique
-- index is the invariant), referencing a live menu or carrying no menu
-- (NULL = the slot is unassigned — assignNavigation(menu: null) detaches).
--
-- House shapes: the V96 attribution rules (full BaseEntity columns,
-- same-script CHECK validation, partial unique index over live rows,
-- V24-shaped _aud mirror).

CREATE TABLE cms_navigation_slots (
    id          UUID PRIMARY KEY,
    slot        VARCHAR(16) NOT NULL,
    menu_id     UUID REFERENCES cms_menus (id),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_navigation_slots_slot_chk
        CHECK (slot IN ('MAIN', 'SECONDARY'))
);

CREATE UNIQUE INDEX cms_navigation_slots_slot_live_uq
    ON cms_navigation_slots (slot) WHERE is_deleted = FALSE;

CREATE TABLE cms_navigation_slots_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    slot        VARCHAR(16),
    menu_id     UUID,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);
