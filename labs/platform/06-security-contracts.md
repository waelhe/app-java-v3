# 06 — Security Contracts: RFC 9457 / JWT / CSRF / CORS / OWASP (isolated platform reference)

> **Tree refs:** reference 09-api-web (9.x/10.x), 02-web §4-6, 03-security §6-7, client 04-api-integration, 05-security-practices.
> **Sources:** RFC 9457/7519 + Spring Security/CORS + OWASP — official.
> **Format:** `TYPE | RULE | [evidence] | URL`. **Encoding:** UTF-8.

## 1. API errors — RFC 9457 (both channels)

- MUST | Return errors as `application/problem+json` with `type`/`title`/`status`/`detail`/`instance`. | [paraphrase] | rfc-editor.org/rfc/rfc9457
- MUST | All client channels decode Problem Details identically (read `type`/`title`/`status`, treat extensions as context). | [paraphrase] | client/04-api-integration.md
- AVOID | Leaking internals (stack traces, SQL, secrets) in `detail`/extensions. | [paraphrase] | rfc-editor.org/rfc/rfc9457

## 2. JWT (both channels)

- MUST | Unique claim names; `jti` for replay/tracking; `cty` for nesting; deliberate `alg` (avoid `none`); validate expiry/`iat` with bounded skew; no PII without need. | [paraphrase] | rfc-editor.org/rfc/rfc7519
- MUST | Validate signature/iss/aud/exp on the resource server; enforce exact redirect-URI matching on the SAS. | [paraphrase] | reference/03-security.md §4-5

## 3. CSRF / CORS / headers

- MUST | In Boot/Next.js BFF: CSRF enabled by default for unsafe methods; a non-browser backend may disable it with a documented reason. | [paraphrase] | reference/03-security.md §6
- MUST | CORS in env for the web origin (finite/`allowOriginPatterns`, never `*` with credentials); mobile needs no CORS. | [paraphrase] | reference/02-web.md §4
- MUST | Security response headers via the backend chain (permit, don't ignore endpoints). | [paraphrase] | reference/03-security.md §6

## 4. SPA/mobile security (OWASP)

- MUST | Validate at the boundary (A03); object-level authorization (A01/IDOR) on the backend for both channels. | [paraphrase] | reference/09-api-web.md §4
- MUST | No tokens in localStorage; server-side storage (BFF) for web, device secure storage for mobile. | [paraphrase] | client/03-token-handling.md · client/05-security-practices.md
- MUST | Secrets via env/secret-manager, never committed; encrypt in transit always (TLS). | [paraphrase] | reference/09-api-web.md §5

## 5. Verification note
- Contracts from RFC/OWASP/Spring official docs — cross-referenced in `labs/reference/` and `labs/client/`.