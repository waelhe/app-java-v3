-- The refresh-token rotation's REUSE DETECTION (the shard review
-- 2026-11-01, Security & Privacy): every completed rotation records the
-- CONSUMED (presented) value's SHA-256 under its authorization family —
-- the oauth2_authorization row IS the family (one row per authorization;
-- each rotation replaces refresh_token_value on that same row). A later
-- presentation of an already-consumed value is a THEFT SIGNAL: the whole
-- family is revoked (the authorization row removed — access token,
-- successor refresh tokens and all) and a security event is logged. The
-- value is stored ONLY as a hash: no bearer secret at rest, and the
-- on-delete cascade keeps the record's lifetime bounded by the family's
-- own lifetime.
CREATE TABLE IF NOT EXISTS consumed_refresh_tokens (
    authorization_id VARCHAR(100) NOT NULL,
    token_hash       BYTEA        NOT NULL,
    -- The 2026-11-05 #806 round-3 review (Minor): clock_timestamp()
    -- records the true statement instant; CURRENT_TIMESTAMP is the
    -- TRANSACTION-start time, so a commit delayed past the 30s grace
    -- window would have a post-commit replay classified as a LATE
    -- theft-shaped replay by the window's own arithmetic.
    consumed_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (token_hash),
    CONSTRAINT fk_consumed_refresh_tokens_authorization
        FOREIGN KEY (authorization_id) REFERENCES oauth2_authorization (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_consumed_refresh_tokens_authorization
    ON consumed_refresh_tokens (authorization_id);
