-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
-- D-ADDR-001 adopted decision): the user-account ADDRESS BOOK — the identity
-- owner's own address rows for the Saleor Users-domain address lifecycle
-- (accountAddress*/address* mutations + the address read root).
--
-- WHY identity owns it (the ADR's measured facts): the platform's only
-- address-bearing tables today are booking/listing-scoped (property
-- addresses inside the booking and real-estate modules — a listing's
-- location is NOT a user account address) and provider business addresses
-- (V96 business_hours' provider_profiles scope — a provider profile is not
-- a user account either). No table in the store carries a USER-account
-- address. Saleor's address roots are user-account-scoped (the account's
-- own address book with default shipping/billing flags), so the single
-- identity store (the users owner) is the owner of record — one new table,
-- no second identity store, no cross-module reach.
--
-- House shapes (the V96 attribution rules verbatim):
--   * Full BaseEntity columns from day one: is_deleted, version,
--     created_by/at, updated_by/at.
--   * CHECKs NOT VALID + VALIDATE in the same script (new empty tables).
--   * Partial unique index over live rows only (WHERE is_deleted = FALSE)
--     for the one-default-per-type invariant pair.
--   * The _aud mirrors in the V24 shape: all columns nullable.
--   * country is the Saleor CountryCode enum's own two-letter ISO 3166-1
--     alpha-2 vocabulary (validated at the GraphQL boundary by the enum
--     itself; the column keeps the raw code — no country registry table
--     is invented).
--   * Saleor's non-null wire fields stay nullable here ONLY where the
--     platform genuinely owns no fact yet; the fields this owner really
--     requires (the geographic spine) are NOT NULL.

CREATE TABLE user_addresses (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users (id),
    first_name              VARCHAR(200),
    last_name               VARCHAR(200),
    company_name            VARCHAR(200),
    street_address_1        VARCHAR(500),
    street_address_2        VARCHAR(500),
    city                    VARCHAR(200),
    city_area               VARCHAR(200),
    postal_code             VARCHAR(40),
    country                 VARCHAR(2) NOT NULL,
    country_area            VARCHAR(200),
    phone                   VARCHAR(60),
    is_default_shipping     BOOLEAN NOT NULL DEFAULT FALSE,
    is_default_billing      BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted              BOOLEAN NOT NULL DEFAULT FALSE,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_by              VARCHAR(200),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              VARCHAR(200),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_addresses_country_chk
        CHECK (country ~ '^[A-Z]{2}$')
);

CREATE INDEX user_addresses_user_idx ON user_addresses (user_id) WHERE is_deleted = FALSE;

-- The one-default-per-user-per-type invariant: partial UNIQUE indexes over
-- live rows express "at most one default of each type per account" directly
-- (the same guard V250's customer_types_one_default_live_uq carries). The
-- owning service still serializes the flip under the user row's lock and
-- orders every swap clears-before-set (Hibernate's action queue executes
-- updates in registration order), so the guard is the backstop, never the
-- working path; these indexes carry the default read paths too.
CREATE UNIQUE INDEX user_addresses_user_default_shipping_live_uq
    ON user_addresses (user_id) WHERE is_deleted = FALSE AND is_default_shipping = TRUE;
CREATE UNIQUE INDEX user_addresses_user_default_billing_live_uq
    ON user_addresses (user_id) WHERE is_deleted = FALSE AND is_default_billing = TRUE;

CREATE TABLE user_addresses_aud (
    id                      UUID NOT NULL,
    rev                     INTEGER NOT NULL,
    revtype                 SMALLINT,
    user_id                 UUID,
    first_name              VARCHAR(200),
    last_name               VARCHAR(200),
    company_name            VARCHAR(200),
    street_address_1        VARCHAR(500),
    street_address_2        VARCHAR(500),
    city                    VARCHAR(200),
    city_area               VARCHAR(200),
    postal_code             VARCHAR(40),
    country                 VARCHAR(2),
    country_area            VARCHAR(200),
    phone                   VARCHAR(60),
    is_default_shipping     BOOLEAN,
    is_default_billing      BOOLEAN,
    is_deleted              BOOLEAN,
    version                 BIGINT,
    created_by              VARCHAR(200),
    created_at              TIMESTAMPTZ,
    updated_by              VARCHAR(200),
    updated_at              TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);
