# 02 — Security Architecture (isolated system map)

> **3 chains, SAS/JWT, method security, CORS, secrets.** Citations are `file:line`.

## 1. The three SecurityFilterChains (single authority: SecurityConfig)

- MUST | Chain 1 `@Order(1)` — authorization server endpoints (`getEndpointsMatcher()`) + OIDC; `anyRequest().authenticated()`; login entry point for HTML only. | [code] | SecurityConfig.java:107-125
- MUST | Chain 2 `@Order(2)` — resource server on `/api/**`, `/actuator/**`, `/graphql`, `/v3/api-docs/**`; CSRF disabled for this matcher set; STATELESS; `oauth2ResourceServer(jwt(...))`. | [code] | SecurityConfig.java:127-164
- MUST | Chain 3 `@Order(3)` — catch-all: `/assets/**`, `/login` permitAll; formLogin; session concurrency via Redis-backed registry. | [code] | SecurityConfig.java:166-192
- MUST | Method security enabled globally: `@EnableMethodSecurity(proxyTargetClass=true)`; 49 `@PreAuthorize` in main (roles + `ownsProvider`), zero `@PostAuthorize`. | [code] | SecurityConfig.java:88

## 2. URL rules (chain 2)

- MUST | permitAll: `GET /api/v1/listings/**`, `GET /api/v1/reviews/**`, `GET /api/v1/search/**`, `GET /api/v1/geo/**`, `GET /actuator/health/**`, `GET /actuator/info`, `GET /v3/api-docs`, `/api/v1/payments/webhooks/**` (signature-gated, anonymous by contract). | [code] | SecurityConfig.java:138-148
- MUST | `/api/v1/admin/** → hasRole("ADMIN")` (+ method-level `@PreAuthorize` = defense in depth). | [code] | SecurityConfig.java:149
- MUST | Everything else: `anyRequest().authenticated()` (notably `GET /api/v1/providers/{id}` and media reads still need a token). | [code] | SecurityConfig.java:150
- MUST | 401/403 return RFC 7807 ProblemDetails (`WWW-Authenticate: Bearer`, `insufficient_scope`); no detail leakage. | [code] | SecurityConfig.java:266-319

## 3. SAS / clients / JWT

- MUST | `JdbcRegisteredClientRepository` + JDBC authorization/consent services (no in-memory). | [code] | SecurityConfig.java:348-363
- MUST | Confidential BFF client: `client_secret_basic`, grants code+refresh+credentials, PKCE + consent required, TTLs 900s/7d/300s, bcrypt secret, idempotent save. | [code] | OAuth2ClientSecretInitializer.java:183-228
- MUST | Public client: `NONE` auth, `authorization_code` only (no refresh), PKCE mandatory, exact redirect env, prod fail-fast. | [code] | OAuth2PublicClientInitializer.java:150-190
- MUST | JWT decoder: co-located JWK + `createDefaultWithIssuer(iss)` + audience `anyMatch` validator; roles flattened (`roles` claim, no prefix on mint, `ROLE_` on verify). | [code] | SecurityConfig.java:493-530,562-574,218-227
- MUST | Keystore: b64 wins over path; single active RSA in `ImmutableJWKSet`; ephemeral key ONLY non-prod; prod fail-fast (INV-7/D6) on blank or half-config. | [code] | SecurityConfig.java:365-479
- MUST | `IdentityUserProvider`: JWT-sub → `findBySubject` → User id; `AuthHelper.ownsProvider` compares in `users.id` space (A1 contract). | [code] | IdentityUserProvider.java:19-34 · AuthHelper.java:9-52
- MUST | Issuer dynamic via env (`${AUTH_SERVER_ISSUER}`); SAS settings from framework (no manual bean). | [code] | application.yml:84-87

## 4. CORS / actuator / secrets

- MUST | CORS: exact origins from `marketplace.cors.allowed-origins`, methods GET/POST/PUT/PATCH/DELETE/OPTIONS, headers incl. `X-API-Version`, `allowCredentials=true`, maxAge 3600; same list on `/ws`. | [code] | SecurityConfig.java:200-216 · WebSocketConfig.java:28-30
- MUST | Actuator: base exposes health/info/metrics/prometheus/modulith; prod on port 8081 without `modulith`, `show-details: never`; only health/info are permitAll, rest need a token. | [code] | application.yml:189-212 · application-prod.yml:114-131 · SecurityConfig.java:145-146
- MUST | Secrets ONLY in env (DB, keystore, clients, HMAC, PSP, S3, mail, OTLP); policy doc + runbook + gitleaks gate; prod log scrubbing. | [code] | docs/security/secrets-policy.md · keys/README.md · application-prod.yml:108

## 5. Security gates (evidenced)

- MUST | Webhooks: generic HMAC (constant-time `MessageDigest.isEqual`, missing-secret → reject) + Stripe official `constructEvent` on raw bytes; dedup via `UNIQUE(provider,event_id)` + REQUIRES_NEW recorder. | [code] | PaymentWebhookSecurity.java:19-47 · StripePspChannel.java:127-138 · V42
- MUST | Ownership: `verifyOwnership`/`ownsProvider` + method security; the A1 id-space fix removed `ProviderLookupPort` from the service (tested). | [code] | CatalogService.java:556-564 · AuthHelper.java:33-45
- MUST | Rotation: JWT r3 via b64; HMAC keyring (`deriveAll`) keeps retired keys blocking re-provisioning. | [code] | MarketplaceProperties.java:60-70
- ATTEND | Inert-until-bound providers return 503 SU-001 (Stripe/media/mail/FX/pseudonymize) — by design, not broken. | [code] | application.yml:355-440