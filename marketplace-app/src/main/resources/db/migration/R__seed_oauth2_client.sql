-- Seed default OAuth2 client for local/dev environments.
-- Rotate secret immediately after bootstrap.
insert into oauth2_registered_client (
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
values (
    'f14b76d6-e449-48f8-bb4f-7fd76729c1da',
    'marketplace-web-client',
    current_timestamp,
    '{noop}change-me',
    'Marketplace Web Client',
    'client_secret_basic',
    'authorization_code,refresh_token,client_credentials',
    'http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client',
    'http://127.0.0.1:8080/',
    'openid,profile,marketplace.read,marketplace.write',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":false,"settings.client.require-authorization-consent":true}',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.access-token-time-to-live":["java.time.Duration",900],"settings.token.refresh-token-time-to-live":["java.time.Duration",604800],"settings.token.reuse-refresh-tokens":true}'
)
on conflict (id) do nothing;
