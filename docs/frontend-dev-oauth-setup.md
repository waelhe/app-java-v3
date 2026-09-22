# Frontend Dev OAuth Setup (write-path testing)

**Goal:** give the `web-marketplace` (Next.js BFF) team working dev credentials so login — and every write path behind it — works locally. Zero backend code changes: this recipe drives the existing `OAuth2ClientSecretInitializer` flow (`marketplace-platform-infra/.../security/OAuth2ClientSecretInitializer.java:111-174`).

## Backend (local dev, 5 minutes)

Set three environment variables on the local backend and restart it:

```bash
OAUTH_CLIENT_ID=marketplace-bff
OAUTH_CLIENT_SECRET=<any-dev-only-value-never-commit>
OAUTH_CLIENT_REDIRECT_URIS=http://localhost:3000/api/auth/callback/marketplace-web
```

On boot the initializer creates (or converges) the `marketplace-bff` row — watch for:

```
Bootstrapped registered client 'marketplace-bff' from environment configuration
```

Guarantees (measured, not assumed):

- Both blank → no row, silent (dev default; login unavailable, reads only).
- One blank → startup throws (misconfiguration is loud, never half-wired).
- Row exists with a different secret → the boot **rotates** it to the env value (env always wins; no manual DB cleanup).
- Production is unaffected: `application-prod.yml` fail-fasts on its own `OAUTH_CLIENT_*` env (separate secrets, separate rows).

## Frontend (local `.env`, git-ignored)

```bash
OAUTH_CLIENT_ID=marketplace-bff
OAUTH_CLIENT_SECRET=<same-value-as-backend>
BACKEND_URL=http://localhost:8080
# redirectURI default already matches: http://localhost:3000/api/auth/callback/marketplace-web
```

Log in → every write path (listings, messages, edits) is testable. The secret is dev-only: never commit `.env`, never reuse it in staging/prod.

## Production (already live, no action)

`OAUTH_CLIENT_ID=marketplace-bff` with the prod callback set (app + web-marketplace origins) is registered and measured working (authorize 302, token 200). This recipe changes nothing there.
