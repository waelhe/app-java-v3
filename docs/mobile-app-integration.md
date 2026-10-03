# Mobile app integration — handoff for the mobile developer

Backend: Spring Boot 4.1.1 + Spring Security 7.1.1 OAuth2 Authorization
Server module. This document is the contract — build against it, not
against examples. All values below were verified live on 2026-10-02
unless marked otherwise.

## 0. Environments (read first)

| Environment | Base URL | Status |
|---|---|---|
| Production | `https://app-java-v3-production-59bf.up.railway.app` | LIVE (liveness 200, verified) |
| Staging | — | **does not exist, by owner decision.** Mobile dev targets production with test accounts. Keep test data clearly marked and delete it after. |

## 1. OAuth client card (exact values from the database)

- `client_id = marketplace-mobile`
- Type: **public client** (`client_authentication_methods = none`) — there is
  **no client secret**. Never send one, never ask for one.
- Grants: `authorization_code` + `refresh_token` (rotating, 90-day sliding window).
- Scopes: `openid profile`
- PKCE: **mandatory**, `S256` only (advertised by discovery).
- Redirect URI (byte-exact — OAuth matches it as a string):
  `com.marketplace.app:/oauth2/callback`
  Single slash after the scheme, path `/oauth2/callback`. Any other form
  (`...://oauth2redirect`, extra slashes, different path) is rejected.

## 2. Login flow (Custom Tabs — never WebView, never embedded passwords)

1. Open the system browser (Custom Tabs) on:
   `{base}/oauth2/authorize?response_type=code&client_id=marketplace-mobile&redirect_uri=com.marketplace.app:/oauth2/callback&scope=openid%20profile&state=<random>&nonce=<random>&code_challenge=<S256>&code_challenge_method=S256`
2. User logs in and approves consent (shown once per user, remembered after).
3. The app receives the `code` at its redirect URI. Code lives **5 minutes**,
   single use.
4. Exchange it — **no Authorization header** (public client):
   `POST {base}/oauth2/token` with form fields
   `grant_type=authorization_code`, `code`, `redirect_uri` (same exact value),
   `client_id=marketplace-mobile`, `code_verifier`.
5. Response contains `access_token` (JWT, **15 minutes**) + `id_token` +
   `refresh_token` (opaque, **90-day sliding window, rotating**: each use
   returns a new refresh token and kills the old one). Store the refresh in
   platform secure storage (Android Keystore-backed EncryptedSharedPreferences).
   On access expiry, `POST {base}/oauth2/token` with
   `grant_type=refresh_token`, `refresh_token`, `client_id=marketplace-mobile`
   — no secret, no verifier. Browser re-login is needed only after 90 days
   of inactivity, explicit logout, or revocation. Do NOT cache the code, do
   NOT retry an exchange twice, do NOT reuse a refresh token twice.

## 3. Calling the API

- Base URL = environment base URL above. Send `Accept: application/json`.
- Every protected call: `Authorization: Bearer <access_token>`.
- Anonymous calls to protected paths return **302** (not 401) — treat a 302
  as "session gone, go to login", same as a 401.
- Lists are paged (`page/size/sort` query params, `PagedResponse` envelope).
- Errors are RFC 7807 `application/problem+json`
  (`errorCode/category/traceId/userMessage`). On any error report, send back
  the `traceId` + app version + request path — never just a screenshot.
- Full contract: `{base}/v3/api-docs` (OpenAPI, 115+ paths on production).
  Build DTOs from it. Do not invent endpoints.

## 4. OkHttp wiring (Retrofit sits on top)

- ONE shared `OkHttpClient`: connect 10s, read 30s, write 15s.
- `Interceptor` injects the Bearer token. An `Authenticator` performs
  **single-flight refresh**: one coroutine refreshes (Mutex) while the rest
  wait and retry with the new access token — parallel refreshes race by
  design, because rotation kills the loser's token. Only if refresh itself
  fails (`invalid_grant`) route to browser re-login.
- If several requests get 401/302 at once, serialize (one refresh, not three).
- Logging interceptor at BODY **only in debug builds**; production builds log
  nothing, and `Authorization` must never reach logs or crash reports.

## 5. Testing checklist (all against staging once it exists; production only
with test accounts)

- [ ] Login returns to the app with a code, exchange succeeds first try.
- [ ] Second exchange of the same code fails (single use) without crashing.
- [ ] Calls after 15 minutes trigger one silent refresh (no browser) and succeed.
- [ ] Reusing an old refresh token fails with `invalid_grant` (rotation proven).
- [ ] Wrong redirect URI string is rejected (proves exact-match understanding).
- [ ] Paged list renders page 2; error screen shows `userMessage`, logs carry `traceId`.

## 6. Forbidden

- Inventing endpoints or query params not in `/v3/api-docs`.
- Any CORS configuration (native apps are not subject to CORS).
- Cleartext HTTP anywhere except a local emulator build.
- Logging tokens, codes, or verifiers.
- `http://localhost:3000/...` callbacks — those belong to the web client.

## 7. Open backend items (owner decides, not the mobile dev)

- **Refresh grant for `marketplace-mobile`**: done — `refresh_token` grant
  with 90-day rotating refresh, live after the next production deploy.
  Browser re-login only after 90 days idle, logout, or revocation.
- **Staging environment**: closed by owner decision — mobile development
  targets production with test accounts.
