-- Phase 4: MFA / 2FA Support
-- RFC 6238 (TOTP) + Recovery Codes

-- MFA secrets table (stores TOTP shared secrets)
CREATE TABLE IF NOT EXISTS mfa_secrets (
    id              uuid NOT NULL PRIMARY KEY,
    user_id         uuid NOT NULL UNIQUE,
    secret          varchar(64) NOT NULL,
    enabled         boolean NOT NULL DEFAULT false,
    is_deleted      boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_by      varchar(200),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      varchar(200),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mfa_secrets_user ON mfa_secrets (user_id) WHERE is_deleted = false;

-- Recovery codes (single-use, hashed)
CREATE TABLE IF NOT EXISTS recovery_codes (
    id              uuid NOT NULL PRIMARY KEY,
    user_id         uuid NOT NULL,
    code_hash       varchar(128) NOT NULL,
    used            boolean NOT NULL DEFAULT false,
    is_deleted      boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_by      varchar(200),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      varchar(200),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_recovery_codes_user ON recovery_codes (user_id) WHERE is_deleted = false AND used = false;

ALTER TABLE mfa_secrets ADD CONSTRAINT fk_mfa_secrets_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE recovery_codes ADD CONSTRAINT fk_recovery_codes_user FOREIGN KEY (user_id) REFERENCES users (id);
