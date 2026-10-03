-- W2 (yelp-level-plan §5 — the business page): the verification state and
-- the dynamic category-attribute registry — the two remaining schema
-- deltas of the wave's roadmap row.
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * The provider_profiles column addition follows the V56 form (ALTER
--     the audited table and its _aud mirror together; nullable in the
--     mirror — a DEL revision carries the id alone, the V24/V54 rule).
--   * The CHECK follows the V78 precedent EXACTLY: ADD NOT VALID then
--     VALIDATE in the same script. V78 is the house's own measured
--     decision for a state-lifecycle CHECK on a live, small table — the
--     V56/V66 split exists for scan-heavy tables; provider_profiles is
--     the platform's provider roster (the same scale class V78 accepted
--     for neighborhood_memberships), and the plan allocates W2 exactly
--     two migrations (V88 + V89 — the 12-migration horizon §5).
--   * The registry table follows V84 (new table: CHECKs NOT VALID then
--     inline VALIDATE — metadata-only on an empty table) and V70
--     (categories: reference data whose vocabulary evolves by INSERT,
--     never by migration — «السمات بيانات», the plan's own principle).
--
-- Access rights: the verification transitions ride the provider module's
-- admin surface (the ProviderController /admin/providers precedent); the
-- attribute registry rides the catalog module's admin surface (the
-- categories registry's own home).

-- ---------------------------------------------------------------------------
-- Provider ownership verification (G14): the Yelp «مالك موثّق» badge's
-- state column. The plan's wording: «حالة توثيق المزود». Four states in
-- the V78 lifecycle shape — the closest measured precedent in the tree:
--   UNVERIFIED — the default every existing profile keeps (zero visible
--                behavior change — the W0 seed-mode rule);
--   PENDING    — the owner submitted a verification claim (the license
--                text they already carry is the claim's evidence);
--   VERIFIED   — an administrator confirmed ownership (the badge lights);
--   REJECTED   — an administrator declined the claim (the owner may
--                submit again — a new PENDING row of history).
--
-- Display-only trust signal, exactly like license_number (V56): no
-- privilege attaches to the badge — the VERIFIED profile status (the
-- existing lifecycle) stays the gate for listings and page inventory.
-- ---------------------------------------------------------------------------

ALTER TABLE provider_profiles
    ADD COLUMN IF NOT EXISTS verification_state VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED';

ALTER TABLE provider_profiles_aud
    ADD COLUMN IF NOT EXISTS verification_state VARCHAR(30);

ALTER TABLE provider_profiles
    ADD CONSTRAINT chk_provider_profiles_verification_state
        CHECK (verification_state IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED')) NOT VALID;

ALTER TABLE provider_profiles
    VALIDATE CONSTRAINT chk_provider_profiles_verification_state;

-- ---------------------------------------------------------------------------
-- category_attributes (G15): the dynamic per-category attribute registry
-- — the plan's wording: «سمات فئة ديناميكية (جدول سمات لكل فئة — G15،
-- مبدأ "السمات بيانات" نفسه)».
--
-- The categories table (V70) is the closed side of the vocabulary: what
-- a listing may classify as. This table is its open side: WHAT structured
-- attributes a category's listings carry (wifi, parking, electronic
-- payment) — attribute DEFINITIONS as data rows, so a new attribute for
-- a category is an INSERT by an administrator, never a migration
-- (the categories' own lifecycle rule, applied one level deeper).
--
-- code       — the stable API-facing key (the V70 categories shape:
--              lowercase latin/digits/dashes; identity per category —
--              never recycled while soft-deleted);
-- value_type — the declared shape of the attribute's VALUE: TEXT, NUMBER,
--              or BOOLEAN. Three types, a closed CHECK like every house
--              vocabulary; a fourth (say, range) is a migration-widened
--              decision, not a silent insertion.
-- position    — the registry's display order (the D-N5 deterministic
--              order key, unique per category over the live rows).
-- ---------------------------------------------------------------------------

CREATE TABLE category_attributes (
    id          UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES categories (id),
    code        VARCHAR(50) NOT NULL,
    label_en    VARCHAR(100),
    label_ar    VARCHAR(100),
    value_type  VARCHAR(20) NOT NULL,
    position    INTEGER NOT NULL DEFAULT 0,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The V70 code shape: a lowercase dotted-free slug.
    CONSTRAINT chk_category_attributes_code
        CHECK (code = lower(code)
           AND length(code) BETWEEN 1 AND 50
           AND code ~ '^[a-z][a-z0-9-]*$') NOT VALID,
    -- The closed value-type vocabulary.
    CONSTRAINT chk_category_attributes_value_type
        CHECK (value_type IN ('TEXT', 'NUMBER', 'BOOLEAN')) NOT VALID,
    -- The display order key is non-negative.
    CONSTRAINT chk_category_attributes_position
        CHECK (position >= 0) NOT VALID
);

ALTER TABLE category_attributes VALIDATE CONSTRAINT chk_category_attributes_code;
ALTER TABLE category_attributes VALIDATE CONSTRAINT chk_category_attributes_value_type;
ALTER TABLE category_attributes VALIDATE CONSTRAINT chk_category_attributes_position;

-- The attribute's identity: one code per category over the live rows
-- (the V70 uq_categories_code shape, scoped one level deeper).
CREATE UNIQUE INDEX uq_category_attributes_category_code
    ON category_attributes (category_id, code)
    WHERE is_deleted = FALSE;

-- The registry's own read: one category's attributes in order.
CREATE INDEX idx_category_attributes_category_position
    ON category_attributes (category_id, position)
    WHERE is_deleted = FALSE;

CREATE TABLE category_attributes_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    category_id UUID,
    code        VARCHAR(50),
    label_en    VARCHAR(100),
    label_ar    VARCHAR(100),
    value_type  VARCHAR(20),
    position    INTEGER,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
