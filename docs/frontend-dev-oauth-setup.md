# Frontend OAuth Setup (staging + any future frontend)

**Setup:** frontend teams develop against the shared **staging** backend
(`https://app-java-v3-staging-staging.up.railway.app`), never against local
backend checkouts and never against production. Staging owns its Neon branch,
its `marketplace-web-staging` client row, and its secret — all managed
backend-side.

## 1. Frontend `.env` (local, git-ignored)

```bash
OAUTH_CLIENT_ID=marketplace-web-staging
OAUTH_CLIENT_SECRET=<staging-secret-from-backend-team>
BACKEND_URL=https://app-java-v3-staging-staging.up.railway.app
# Send this exact redirect_uri:
# http://localhost:3000/api/auth/callback/marketplace-web
```

Backend requirement (verified live): `OAUTH_CLIENT_REDIRECT_URIS` holds the
localhost callback and the row is confidential (`client_secret_basic`) —
strict redirect-URI matching rejects anything else.

Log in from `http://localhost:3000` → every write path testable against
isolated staging data. Staging secret is dev-only: never commit `.env`,
never reuse it in production (`marketplace-bff` + Railway-managed secret).

## 2. Reusable contract (measured, not assumed)

- Endpoints: `/.well-known/openid-configuration`, `/oauth2/authorize`,
  `/oauth2/token`, `/oauth2/jwks`, `/userinfo` (discovery advertises them;
  issuer equals the staging host — ID-token `iss` must match byte-for-byte).
- PKCE: `S256` only (row requires proof key; `none` rejected — discovery
  omits `none` from client auth methods).
- Consent: required once per (client, user); remembered afterwards (standard
  SAS behavior, not a bug).
- Windows: authorization code 5 min, single use. Anonymous POSTs without
  CSRF get 302 to `/login` by design (CsrfFilter before client auth) — only
  real browser flows prove the token exchange, never bare curl.
- Health: aggregate `/actuator/health` may read 503 while
  `/actuator/health/liveness` is UP (contributor-dependent, mirrors prod).

## 3. Test users (backend-owned pattern)

- Accounts live in `auth_users` (+ `auth_authorities`); identity rows derive
  automatically at first `/me` (`syncFromOidc`).
- Password hashes MUST carry the `{bcrypt}` prefix —
  `DelegatingPasswordEncoder` throws on bare hashes and kills boot
  (measured staging outage). Always verify with `bcrypt.checkpw` first.
- New frontends reuse `qa-tester`-style accounts (CONSUMER+PROVIDER);
  request them from the backend team — no signup surface exists (see §5).

## 4. Adding ANOTHER frontend (no code changes)

The backend currently manages exactly one confidential + one public client
(single env-driven slots). Additional dev frontends **share**
`marketplace-web-staging`: append its callback to the comma-separated
`OAUTH_CLIENT_REDIRECT_URIS` (initializer parses lists; converge is
automatic on next boot). A second *production* confidential client needs
the multi-client extension (deferred architectural decision — single-slot
design collides on cloned DBs: stable row UUID vs copied rows).

## 5. Signup: deliberately absent

No `POST /register` exists backend- or frontend-side (measured across both
repos). Onboarding is closed: seed + admin/user creation by the backend
team. Opening it is an architectural decision (abuse controls, provider
verification queue, invite tokens) — not a missing endpoint.

## 6. Domain hygiene (learned the hard way)

Verify every base URL live before measuring: a dead domain
(`…-d020…`, remnant of a deleted service) once produced 502s
misattributed to the backend. `liveness 200 + discovery 200` first,
conclusions second.
