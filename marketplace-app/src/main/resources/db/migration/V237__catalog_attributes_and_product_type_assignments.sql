-- C1: reusable typed attribute definitions, choices, and product-type assignments.
-- The schema differentiates product fields, per-variant facts, and buyer-selectable
-- variant options. The service layer enforces that VARIANT_SELECTION uses CHOICE
-- definitions with at least two allowed options; relational CHECKs guard vocabularies.
-- This follows V236 ProductType and is also downstream of the in-flight V235 (#654).
CREATE TABLE catalog_attribute_definitions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50) NOT NULL,
    name_en     VARCHAR(100),
    name_ar     VARCHAR(100),
    value_type  VARCHAR(16) NOT NULL,
    description VARCHAR(1000),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_catalog_attr_code CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9-]*$') NOT VALID,
    CONSTRAINT chk_catalog_attr_type CHECK (value_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'CHOICE')) NOT VALID,
    CONSTRAINT chk_catalog_attr_label CHECK (nullif(btrim(name_en), '') IS NOT NULL OR nullif(btrim(name_ar), '') IS NOT NULL) NOT VALID
);
ALTER TABLE catalog_attribute_definitions VALIDATE CONSTRAINT chk_catalog_attr_code;
ALTER TABLE catalog_attribute_definitions VALIDATE CONSTRAINT chk_catalog_attr_type;
ALTER TABLE catalog_attribute_definitions VALIDATE CONSTRAINT chk_catalog_attr_label;
CREATE UNIQUE INDEX uq_catalog_attribute_definitions_code_live
    ON catalog_attribute_definitions (code) WHERE is_deleted = FALSE;

CREATE TABLE catalog_attribute_definitions_aud (
    id UUID NOT NULL, rev INTEGER NOT NULL, revtype SMALLINT,
    code VARCHAR(50), name_en VARCHAR(100), name_ar VARCHAR(100), value_type VARCHAR(16),
    description VARCHAR(1000), is_deleted BOOLEAN, version BIGINT,
    created_by VARCHAR(200), created_at TIMESTAMPTZ, updated_by VARCHAR(200), updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE catalog_attribute_options (
    id UUID PRIMARY KEY,
    attribute_definition_id UUID NOT NULL REFERENCES catalog_attribute_definitions(id),
    code VARCHAR(50) NOT NULL,
    name_en VARCHAR(100),
    name_ar VARCHAR(100),
    position INTEGER NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_catalog_attr_option_code CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9-]*$') NOT VALID,
    CONSTRAINT chk_catalog_attr_option_label CHECK (nullif(btrim(name_en), '') IS NOT NULL OR nullif(btrim(name_ar), '') IS NOT NULL) NOT VALID,
    CONSTRAINT chk_catalog_attr_option_position CHECK (position >= 0) NOT VALID
);
ALTER TABLE catalog_attribute_options VALIDATE CONSTRAINT chk_catalog_attr_option_code;
ALTER TABLE catalog_attribute_options VALIDATE CONSTRAINT chk_catalog_attr_option_label;
ALTER TABLE catalog_attribute_options VALIDATE CONSTRAINT chk_catalog_attr_option_position;
CREATE UNIQUE INDEX uq_catalog_attribute_options_code_live
    ON catalog_attribute_options (attribute_definition_id, code) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX uq_catalog_attribute_options_position_live
    ON catalog_attribute_options (attribute_definition_id, position) WHERE is_deleted = FALSE;
CREATE TABLE catalog_attribute_options_aud (
    id UUID NOT NULL, rev INTEGER NOT NULL, revtype SMALLINT,
    attribute_definition_id UUID, code VARCHAR(50), name_en VARCHAR(100), name_ar VARCHAR(100),
    position INTEGER, is_deleted BOOLEAN, version BIGINT,
    created_by VARCHAR(200), created_at TIMESTAMPTZ, updated_by VARCHAR(200), updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE product_type_attribute_assignments (
    id UUID PRIMARY KEY,
    product_type_id UUID NOT NULL REFERENCES product_types(id),
    attribute_definition_id UUID NOT NULL REFERENCES catalog_attribute_definitions(id),
    scope VARCHAR(24) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_type_attr_scope CHECK (scope IN ('PRODUCT', 'VARIANT', 'VARIANT_SELECTION')) NOT VALID,
    CONSTRAINT chk_product_type_attr_position CHECK (position >= 0) NOT VALID
);
ALTER TABLE product_type_attribute_assignments VALIDATE CONSTRAINT chk_product_type_attr_scope;
ALTER TABLE product_type_attribute_assignments VALIDATE CONSTRAINT chk_product_type_attr_position;
CREATE UNIQUE INDEX uq_product_type_attribute_assignment_live
    ON product_type_attribute_assignments (product_type_id, attribute_definition_id) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX uq_product_type_attribute_position_live
    ON product_type_attribute_assignments (product_type_id, scope, position) WHERE is_deleted = FALSE;
CREATE TABLE product_type_attribute_assignments_aud (
    id UUID NOT NULL, rev INTEGER NOT NULL, revtype SMALLINT,
    product_type_id UUID, attribute_definition_id UUID, scope VARCHAR(24), is_required BOOLEAN,
    position INTEGER, is_deleted BOOLEAN, version BIGINT,
    created_by VARCHAR(200), created_at TIMESTAMPTZ, updated_by VARCHAR(200), updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
