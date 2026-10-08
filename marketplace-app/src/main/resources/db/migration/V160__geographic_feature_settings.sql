-- B-18 (compliance plan C.10 — إعدادات ميزات وارثة جغرافيًا: بلد ← مدينة ← حي):
-- the geographic feature settings table — the console's third settings
-- surface (B-15's feature_flags/remote_config_values sibling), extending
-- the SAME request-time gate discipline with the geographic dimension.
--
-- The row's shape is C.10's own wording (مفتاح ميزة + نطاق جغرافي + قيمة):
-- key_name + location_id + enabled. One LIVE row per (key, location) —
-- the partial unique in the V70/V157 shape (a soft-deleted setting never
-- blocks its own re-registration); the BaseEntity auditing columns are
-- the change history the console's audit read surfaces; the _aud mirror
-- rides the V24 convention (all columns nullable).
--
-- location_id is a plain UUID column with NO FK to geo_locations — the
-- same module decoupling every house cross-module id column carries
-- (V18/V32/V48/V54): the console resolves and gates the location through
-- GeoLookupPort (the port's own 404 BEFORE any write), never through a
-- database-level dependency on the geo module's tables.
--
-- No CHECK constraints: the rows carry no stored vocabulary (the key
-- names the SAME key space the services gate on — the flags' own space;
-- the declared non-FK reasoning is GeographicFeatureSetting's javadoc).
-- The settings VALUES are data managed through the console's admin
-- surface — never migrations (the V70 dictionary discipline verbatim).

CREATE TABLE geographic_feature_settings (
    id          UUID PRIMARY KEY,
    key_name    VARCHAR(200) NOT NULL,
    location_id UUID NOT NULL,
    enabled     BOOLEAN NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One LIVE row per (key, location) — the duplicate pair's backstop; the
-- service's own 409 is the polite face (the 23505⇒409 house translation).
-- The index doubles as the resolution query's point-read index (the
-- nearest-ancestor walk's ONE settings lookup per key+chain).
CREATE UNIQUE INDEX uq_geographic_feature_settings_key_location
    ON geographic_feature_settings (key_name, location_id)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every registration (ADD), flip
-- (MOD), and retirement (DEL) leaves a revision — the trail the console's
-- change-history read surfaces.
CREATE TABLE geographic_feature_settings_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    key_name    VARCHAR(200),
    location_id UUID,
    enabled     BOOLEAN,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
