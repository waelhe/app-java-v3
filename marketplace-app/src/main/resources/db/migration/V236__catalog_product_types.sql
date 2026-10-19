-- C1 (Saleor alignment): begin the catalog-owned reusable goods ProductType
-- dictionary. ProductType is NOT StoreCategory: the latter remains navigation
-- taxonomy. Definitions are data-managed, audited reference data; typed
-- attributes and variant-option assignments are layered on by later C1 steps.
-- V235 is owned by the in-flight store-order delivery PR #654. This migration
-- is V236 by design and MUST NOT be merged/deployed before V235 is reconciled.
CREATE TABLE product_types (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50) NOT NULL,
    name_en     VARCHAR(100),
    name_ar     VARCHAR(100),
    description VARCHAR(1000),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_types_code_shape
        CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9-]*$') NOT VALID,
    CONSTRAINT chk_product_types_display_name
        CHECK (nullif(btrim(name_en), '') IS NOT NULL
            OR nullif(btrim(name_ar), '') IS NOT NULL) NOT VALID
);

ALTER TABLE product_types VALIDATE CONSTRAINT chk_product_types_code_shape;
ALTER TABLE product_types VALIDATE CONSTRAINT chk_product_types_display_name;

-- A deleted definition releases its code; the unique index is the race-safe
-- authority behind the service's friendly duplicate precheck.
CREATE UNIQUE INDEX uq_product_types_code_live
    ON product_types (code) WHERE is_deleted = FALSE;

CREATE TABLE product_types_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    code        VARCHAR(50),
    name_en     VARCHAR(100),
    name_ar     VARCHAR(100),
    description VARCHAR(1000),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
