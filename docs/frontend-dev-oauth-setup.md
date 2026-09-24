# Frontend Dev OAuth Setup (write-path testing)

**Setup:** the frontend team develops against the shared **staging** backend
(`https://app-java-v3-staging-staging.up.railway.app`), never against local
backend checkouts and never against production. The staging environment owns
its Neon branch, its `marketplace-web-staging` client row, and its secret —
all managed backend-side. Zero backend code or env changes are needed on any
dev machine.

## Frontend (local `.env`, git-ignored)

```bash
OAUTH_CLIENT_ID=marketplace-web-staging
OAUTH_CLIENT_SECRET=<staging-secret-from-backend-team>
BACKEND_URL=https://app-java-v3-staging-staging.up.railway.app
# The frontend must send this exact redirect_uri:
# http://localhost:3000/api/auth/callback/marketplace-web
```

Backend requirement (verified live on staging): `OAUTH_CLIENT_REDIRECT_URIS`
must be set to `http://localhost:3000/api/auth/callback/marketplace-web`
and registered for `marketplace-web-staging` — strict redirect-URI matching
rejects anything else (a blank value falls back to a loopback default that
does not match).

Log in from `http://localhost:3000` → every write path (listings, messages,
edits) is testable against isolated staging data. The staging secret is
dev-only: never commit `.env`, never reuse it in production (production uses
`marketplace-bff` with its own Railway-managed secret).

## Backend (already done, no action)

- Staging service + Neon `staging` branch + `marketplace-web-staging` row
  (localhost:3000 callback registered) — live and smoke-tested (discovery
  200, authorize 302, no `invalid_client`).
- Production is untouched: `marketplace-bff` row and prod secret never leave
  Railway; sharing them with dev machines is forbidden (client_credentials
  impersonation + prod-data pollution).
