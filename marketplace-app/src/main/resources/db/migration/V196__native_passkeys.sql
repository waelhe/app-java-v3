-- The credential tables below are copied from Spring Security 7.1.1's
-- official JDBC WebAuthn PostgreSQL schema resources:
-- web/src/main/resources/org/springframework/security/user-entities-schema.sql
-- web/src/main/resources/org/springframework/security/user-credentials-schema-postgres.sql
-- Only the native ceremony challenge store is application-specific; it is
-- required because Android's token grant is stateless and cannot rely on an
-- HttpSession-backed WebAuthn options repository.
create table user_entities
(
    id           varchar(1000) not null,
    name         varchar(100)  not null,
    display_name varchar(200),
    primary key (id)
);

create table user_credentials
(
    credential_id                varchar(1000) not null,
    user_entity_user_id          varchar(1000) not null,
    public_key                   bytea         not null,
    signature_count              bigint,
    uv_initialized               boolean,
    backup_eligible              boolean       not null,
    authenticator_transports     varchar(1000),
    public_key_credential_type   varchar(100),
    backup_state                 boolean       not null,
    attestation_object           bytea,
    attestation_client_data_json bytea,
    created                      timestamp,
    last_used                    timestamp,
    label                        varchar(1000) not null,
    primary key (credential_id)
);

create table native_passkey_challenges
(
    challenge_id   uuid primary key,
    purpose        varchar(32) not null check (purpose in ('AUTHENTICATE', 'REGISTER')),
    principal_name varchar(100),
    options_json   text not null,
    created_at     timestamp with time zone not null default current_timestamp,
    expires_at     timestamp with time zone not null,
    constraint ck_native_passkey_challenge_expiry check (expires_at > created_at)
);

create index ix_native_passkey_challenges_expiry
    on native_passkey_challenges (expires_at);
