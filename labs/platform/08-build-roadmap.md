# 08 — Sequential Build Roadmap (isolated platform reference)

> **Execution plan.** Any AI can follow this to build the platform end-to-end. Binds the decisions
> (`00-decisions.md`) to the contracts (family files) in order. No step assumes code from memory.

## Phase 0 — Locked (decisions + contracts)

1. Load `00-decisions.md` (the four locks: KMP / Next.js+BFF / own SAS / local-first).
2. Load the reference families needed for the current step (`labs/reference/`, `labs/client/`, `labs/platform/`).
3. Confirm the step's contracts before touching anything (citation rule).

## Phase 1 — Register the two OAuth2 clients (backend config only, zero code)

1. Register **Web** client via `RegisteredClientRepository.save`: public/confidential per BFF choice, `authorization_code` + `refresh_token`, redirect-uri, scopes, PKCE default. (client/01-client-types.md, 02-auth-flows.md)
2. Register **Mobile** client: public, `authorization_code` + PKCE, loopback/custom-scheme redirect, no secret. (client/01-client-types.md, rfc 8252)
3. Configure CORS env for the web origin (finite origins; never `*` with credentials). (reference/02-web.md §4)
4. Verification: both clients resolve; PKCE challenge works; CORS headers present for web, absent need for mobile.

## Phase 2 — Build the Web (Next.js + BFF)

1. Scaffold Next.js; the server holds the client secret (env) and performs authorization_code + client_secret. (03-web.md)
2. Keep tokens in the server session; never send them to the browser. (client/03-token-handling.md)
3. Call the shared backend with Bearer via a server-side client interceptor. (client/04-api-integration.md)
4. Decode RFC 9457 Problem Details; on 401, re-auth. (06-security-contracts.md)
5. Verification: login/read-API flow on localhost against the local backend.

## Phase 3 — Build the Mobile (KMP + AppAuth/PKCE)

1. Scaffold the KMP shared module (models, API layer, auth logic) shared by iOS + Android. (04-mobile.md)
2. Implement authorization_code + PKCE via AppAuth; store tokens in device secure storage. (04-mobile.md, client/02-auth-flows.md)
3. Call the SAME shared backend API as the web app (unified channel). (01-backend-core.md)
4. Verification: login/read-API flow on emulators against the local backend (loopback).

## Phase 4 — Full integration test (web + mobile vs the unified backend)

1. Test: login, refresh, logout, revocation, 401 paths on BOTH clients against ONE backend. (client/02-auth-flows.md, 03-token-handling.md)
2. Test CORS (web) + exact redirect matching + Problem Details + IDOR (both paths). (06-security-contracts.md)
3. Move to Railway for real-device tests; update issuer/CORS to the public origin. (07-environment.md)
4. Verification: DoD — both clients fully functional against one backend; no internals leaked; revocation effective.

## Rollback rules

- Any step failing its verification → fix at the root (framework rule or config), never patch the backend.
- Provider migration (if ever) → identity-mapping plan first (see 05-auth-identity.md §4; 00-decisions.md §3.3).