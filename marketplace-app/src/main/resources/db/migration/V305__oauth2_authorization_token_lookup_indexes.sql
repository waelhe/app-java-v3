-- V302__oauth2_authorization_token_lookup_indexes.sql
-- The 2026-11-06 #806 round review (Minor · Performance & Scalability),
-- corrected in-round (the reviewer's own follow-up): the seven-branch OR
-- equality lookup (state, authorization_code_value, access_token_value,
-- oidc_id_token_value, refresh_token_value, user_code_value,
-- device_code_value) runs in JdbcOAuth2AuthorizationService.findByToken
-- ONLY when the caller passes a NULL token type — the token
-- REVOCATION/inspection paths (findByToken(id, tokenType=null)). The
-- refresh leg itself (PlatformTokenGrantService.refresh) passes the
-- non-null REFRESH_TOKEN type, so spring-authorization-server 7.1.2
-- probes refresh_token_value ALONE on that path. V13 created the table
-- with the PRIMARY KEY alone (the official schema ships no secondary
-- indexes), so the null-type revocation lookups scanned the whole table.
--
-- One b-tree per probed arm keeps the seven-branch OR bitmap-indexable —
-- a PARTIAL index set cannot: the planner falls back to a sequential
-- scan for the whole statement when any single arm lacks an index. The
-- arm population splits by the platform's ACTUAL grant mix, not by a
-- single flow: the dedicated password-grant client rows write
-- access_token_value and refresh_token_value ONLY (state,
-- authorization_code_value, oidc_id_token_value, user_code_value and
-- device_code_value stay NULL for them), while the PUBLIC client
-- (authorization_code + PKCE) also writes state and authorization_code_value
-- — the OAuth2 points the platform actually activates today. Keeping all
-- seven indexes makes the null-type lookup indexable whichever arms the
-- activated grant mix fills.
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
