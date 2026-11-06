-- V302__oauth2_authorization_token_lookup_indexes.sql
-- The 2026-11-06 #806 round review (Minor · Performance & Scalability):
-- the anonymous tokenRefresh probe resolves the authorization row through
-- JdbcOAuth2AuthorizationService.findByToken — the stock SEVEN-ARM OR
-- equality lookup (state, authorization_code_value, access_token_value,
-- oidc_id_token_value, refresh_token_value, user_code_value,
-- device_code_value). V13 created the table with the PRIMARY KEY alone
-- (the official spring-authorization-server 7.1.2 schema ships no
-- secondary indexes), so every anonymous refresh scanned the whole
-- table; the per-caller rate limit bounds the request RATE, never the
-- scan cost of each request.
--
-- One b-tree per probed arm makes the OR bitmap-indexable — a PARTIAL
-- index set cannot: the planner falls back to a sequential scan for the
-- whole statement when any single arm lacks an index. The five arms this
-- platform's single-client password-grant flows never write (state,
-- authorization_code_value, oidc_id_token_value, user_code_value,
-- device_code_value) stay all-NULL, so their indexes remain near-empty
-- pages; the two live arms (access_token_value, refresh_token_value)
-- carry the bounded JWT values the issuer mints.
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_state
    ON oauth2_authorization (state);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_code_value
    ON oauth2_authorization (authorization_code_value);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_access_token_value
    ON oauth2_authorization (access_token_value);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_oidc_id_token_value
    ON oauth2_authorization (oidc_id_token_value);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_refresh_token_value
    ON oauth2_authorization (refresh_token_value);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_user_code_value
    ON oauth2_authorization (user_code_value);
CREATE INDEX IF NOT EXISTS ix_oauth2_authorization_device_code_value
    ON oauth2_authorization (device_code_value);
