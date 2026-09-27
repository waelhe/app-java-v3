# Frontend OAuth Setup (staging + any future frontend)

**Setup:** frontend teams develop against the shared **staging** backend
(`https://app-java-v3-staging-staging.up.railway.app`), never against local
backend checkouts and never against production. Staging owns its Neon branch,
its `marketplace-bff` client row, and its secret — all managed
backend-side.

## 1. Frontend `.env` (local, git-ignored)

```bash
OAUTH_CLIENT_ID=marketplace-bff
OAUTH_CLIENT_SECRET=<staging-secret-from-backend-team>
BACKEND_URL=https://app-java-v3-staging-staging.up.railway.app
# Send this exact redirect_uri:
# http://localhost:3000/api/auth/callback/marketplace-web
```

> **Client id truth (measured 2026-09-26/27, write-battery 33 card BE-07):**
> the LIVE confidential client on staging is **`marketplace-bff`** (200 +
> token sweep in the battery's four-way client×environment measurement). The
> earlier `marketplace-web-staging` row still EXISTS on the staging
> authorization server (authorize issues a code) but its token exchange
> answers 401 `invalid_client` — an orphan row whose secret is not the one
> the backend team hands out. A correct secret against the wrong row fails
> exactly this way; the doc previously pointed at the orphan.

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
  request them from the backend team — or self-register via §5 now that
  the surface exists.

## 4. Adding ANOTHER frontend (no code changes)

The backend currently manages exactly one confidential + one public client
(single env-driven slots). Additional dev frontends **share**
`marketplace-web-staging`: append its callback to the comma-separated
`OAUTH_CLIENT_REDIRECT_URIS` (initializer parses lists; converge is
automatic on next boot). A second *production* confidential client needs
the multi-client extension (deferred architectural decision — single-slot
design collides on cloned DBs: stable row UUID vs copied rows).

## 5. Signup: the registration surface exists

`POST /auth/register` is live and published in the OpenAPI contract
(measured: the operation is present in the staging `/v3/api-docs`; added by
the S1/B1 registration surface — profile row and login rows in one
transaction, CONSUMER role, email capped at 50 chars per the login store's
domain, password 8–72 bytes per bcrypt's ceiling). Onboarding is OPEN:
self-registration first, backend-seeded accounts for specific roles on
request. (The previous "deliberately absent" text was true when written —
measured false by write-battery 33 card BE-07 — and is corrected here.)

## 6. Domain hygiene (learned the hard way)

Verify every base URL live before measuring: a dead domain
(`…-d020…`, remnant of a deleted service) once produced 502s
misattributed to the backend. `liveness 200 + discovery 200` first,
conclusions second.
