-- Durable WebAuthn credential state and one-time native passkey challenges.
-- The user_entities/user_credentials table names and columns follow the
-- Spring Security 7.1.1 JdbcPublicKeyCredentialUserEntityRepository and
-- JdbcUserCredentialRepository contracts. PostgreSQL bytea is used for
-- binary key/attestation data instead of the generic sample's blob type.
CREATE TABLE user_entities (
    id VARCHAR(512) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL
);

CREATE TABLE user_credentials (
    credential_id VARCHAR(1024) PRIMARY KEY,
    user_entity_user_id VARCHAR(512) NOT NULL REFERENCES user_entities(id) ON DELETE CASCADE,
    public_key BYTEA NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized BOOLEAN NOT NULL DEFAULT FALSE,
    backup_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    authenticator_transports VARCHAR(256),
    public_key_credential_type VARCHAR(64) NOT NULL DEFAULT 'public-key',
    backup_state BOOLEAN NOT NULL DEFAULT FALSE,
    attestation_object BYTEA NOT NULL,
    attestation_client_data_json BYTEA,
    created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used TIMESTAMP WITH TIME ZONE,
    label VARCHAR(255) NOT NULL
);

CREATE INDEX ix_user_credentials_user_entity_user_id
    ON user_credentials (user_entity_user_id);

CREATE TABLE native_passkey_challenges (
    challenge_id UUID PRIMARY KEY,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('AUTHENTICATE', 'REGISTER')),
    principal_name VARCHAR(255),
    options_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_native_passkey_challenge_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_native_passkey_challenges_expiry
    ON native_passkey_challenges (expires_at);
