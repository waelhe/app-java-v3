# 05 — Security Practices: PKCE, exact redirect, DPoP, SPA security (isolated client reference)

> **Tree refs:** 3.4 (OAuth2 Client), 3.6 (SAS), RFC 7636/8252/9449 + OWASP (XSS/CSRF/CORS) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

## 1. OAuth2 hardening

- MUST | Public clients MUST use PKCE (RFC 7636) — `code_challenge` + `code_verifier` — to prevent authorization-code interception. | [quote] | rfc-editor.org/rfc/rfc7636
- MUST | Validate the redirect URI with exact string matching against pre-registered URIs (SAS side). | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html
- ATTEND | DPoP (RFC 9449) binds the access token to a public key and requires proof of possession — in contrast to a plain bearer token; use it for high-risk or confidential clients. | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html
- MUST | Use a strong token `alg` with a documented decision; avoid `alg: none`. | [paraphrase] | rfc-editor.org/rfc/rfc7519
- AVOID | Putting PII in a JWT without need; prefer references/correlation ids. | [paraphrase] | rfc-editor.org/rfc/rfc7519

## 2. SPA / browser security (OWASP)

- MUST | Never store access/refresh tokens in localStorage/sessionStorage — risk of XSS token theft. | [paraphrase] | OWASP SPA cheat sheet
- MUST | Protect against XSS (input encoding, CSP) — tokens in memory only; keep them server-side via BFF. | [paraphrase] | OWASP SPA cheat sheet
- MUST | Use `SameSite` cookies / CSRF-safe patterns for any cookie-based session — avoid cross-site request forgery. | [paraphrase] | OWASP CSRF cheat sheet
- MUST | Do not use CORS `*` with credentials; use finite origins or `allowOriginPatterns`. | [paraphrase] | OWASP CORS cheat sheet · 02-web.md §4
- AVOID | Trusting client-side only for authorization — object-level checks remain on the backend. | [paraphrase] | OWASP A01

## 3. Project binding [our-convention]

- MUST | Per §0.3 "backend anchored": a new client = configuration + test via `RegisteredClientRepository.save` + CORS env; zero new backend code/modules/dependencies. | [our-convention] | (project constitution)