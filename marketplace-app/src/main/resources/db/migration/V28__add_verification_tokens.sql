-- Verification tokens for email verification and password reset
-- Follows OWASP Authentication Cheat Sheet: tokens must be random, single-use, and time-bound
CREATE TABLE IF NOT EXISTS verification_tokens (
    id              uuid NOT NULL PRIMARY KEY,
    user_id         uuid NOT NULL,
    token           varchar(64) NOT NULL,
    token_type      varchar(30) NOT NULL CHECK (token_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    expiry_date     timestamptz NOT NULL,
    used            boolean NOT NULL DEFAULT false,
    is_deleted      boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_by      varchar(200),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      varchar(200),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_verification_tokens_token ON verification_tokens (token) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_verification_tokens_user ON verification_tokens (user_id) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_verification_tokens_type ON verification_tokens (token_type, used) WHERE is_deleted = false;

ALTER TABLE verification_tokens
    ADD CONSTRAINT fk_verification_tokens_user
    FOREIGN KEY (user_id) REFERENCES users (id);
