# 03 — Web: Next.js + BFF (isolated platform reference)

> **Tree refs:** client 01-client-types, 03-token-handling; reference 02-web §4, 03-security. Generic.
> **Sources:** Next.js + Spring Security OAuth2 Client official. **Format:** `TYPE | RULE | [evidence] | URL`. **Encoding:** UTF-8.

## 1. React vs Next.js

- ATTEND | **React** is a UI **library** (client-side rendering only); **Next.js** is a full **framework** on top of React (adds routing, SSR/SSG, a server layer). | [paraphrase] | nextjs.org (official)
- MUST | Use **Next.js** for a production web app that needs SEO, server-side rendering, and a server layer to act as BFF. | [paraphrase] | nextjs.org (official)

## 2. The BFF role of Next.js

- MUST | The Next.js server acts as the **Backend-for-Frontend**: it holds the client secret and performs the OAuth2 authorization-code + client_secret flow. | [paraphrase] | reference/03-security.md §2 (Spring Security OAuth2 Client) + 02-client-architecture.md
- MUST | Keep access/refresh tokens in the **server session** (Next.js server), never send them to the browser. | [paraphrase] | client/03-token-handling.md (OWASP SPA)
- MUST | The Next.js server calls the shared backend with the Bearer token via a client HTTP interceptor (RestClient/WebClient with OAuth2). | [paraphrase] | client/04-api-integration.md

## 3. Security for the web client

- MUST | Protect the client secret via env/secret-manager (never in the browser or committed). | [paraphrase] | reference/09-api-web.md §5 (OWASP secrets)
- MUST | Configure CORS on the backend for the web origin (finite origins or `allowOriginPatterns`, never `*` with credentials). | [paraphrase] | reference/02-web.md §4
- MUST | Use PKCE on the authorization code flow; the SAS enables PKCE by default for authorization_code clients. | [paraphrase] | client/02-auth-flows.md
- AVOID | Storing tokens in localStorage/sessionStorage — XSS risk; keep them server-side via BFF. | [paraphrase] | client/05-security-practices.md
- MUST | Handle RFC 9457 Problem Details on API errors; on 401, treat as re-auth signal. | [paraphrase] | client/04-api-integration.md

## 4. Verification note
- Next.js usage is from its official docs; OAuth2 client behavior from Spring Security official — cross-referenced in `labs/client/`.