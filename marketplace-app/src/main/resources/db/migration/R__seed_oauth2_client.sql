-- Repeatable seed for the initial admin user.
-- NOTE: rotate seeded passwords immediately in non-local environments.
--
-- The OAuth2 client (marketplace-web-client) is deliberately NOT seeded here. Its sole
-- official bootstrap path is OAuth2ClientSecretInitializer (RegisteredClientRepository.save
-- via the framework builders), because the row's client_settings/token_settings columns
-- hold a Jackson-serialized map whose format is owned by Spring Authorization Server, not
-- this application. Hand-written seed JSON drifted from that format (missing
-- id-token-signature-algorithm/access-token-format), which JwtGenerator rejected on any
-- openid flow. The initializer converges existing rows and bootstraps absent ones.

INSERT INTO auth_users (username, password, enabled)
VALUES ('admin', '{bcrypt}$2a$10$3eQPPvN8p6E0fG6x0Q2BbuAsEeheUSM6nu6G0jTLQVDWjzS4PQBFe', true)
ON CONFLICT (username) DO NOTHING;

INSERT INTO auth_authorities (username, authority)
VALUES ('admin', 'ROLE_ADMIN')
ON CONFLICT (username, authority) DO NOTHING;
