-- Repeatable seed for initial admin user and OAuth2 client
-- NOTE: rotate seeded secrets immediately in non-local environments.

INSERT INTO auth_users (username, password, enabled)
VALUES ('admin', '{bcrypt}$2a$10$3eQPPvN8p6E0fG6x0Q2BbuAsEeheUSM6nu6G0jTLQVDWjzS4PQBFe', true)
ON CONFLICT (username) DO NOTHING;

INSERT INTO auth_authorities (username, authority)
VALUES ('admin', 'ROLE_ADMIN')
ON CONFLICT (username, authority) DO NOTHING;

INSERT INTO oauth2_registered_client (
    id,
    client_id,
    client_id_issued_at,
    client_secret,
    client_name,
    client_authentication_methods,
    authorization_grant_types,
    redirect_uris,
    post_logout_redirect_uris,
    scopes,
    client_settings,
    token_settings
)
VALUES (
    'a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e',
    'marketplace-web-client',
    CURRENT_TIMESTAMP,
    '{noop}change-me-now',
    'Marketplace Web Client',
    'client_secret_basic',
    'authorization_code,refresh_token,client_credentials',
    'http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client',
    'http://127.0.0.1:8080/',
    'openid,profile',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":true}',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.reuse-refresh-tokens":false,"settings.token.access-token-time-to-live":["java.time.Duration",900],"settings.token.refresh-token-time-to-live":["java.time.Duration",604800],"settings.token.authorization-code-time-to-live":["java.time.Duration",300]}'
)
ON CONFLICT (id) DO NOTHING;
