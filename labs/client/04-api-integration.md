# 04 — API Integration & Backend Contract (isolated client reference)

> **Tree refs:** 9.1 (RFC 9457), 9.4 (OAuth2), 02-web.md §4 (CORS) — cross-ref into `labs/reference/`.
> **Sources:** RFC 9457 + Spring Security OAuth2 Client + Spring Boot CORS — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

## 1. Consuming the protected backend API

- MUST | Attach the Bearer token to every backend request via the client HTTP interceptor (RestClient) or exchange filter (WebClient). | [paraphrase] | servlet/oauth2/client/authorized-clients.html
- ATTEND | On `401`/`invalid_token`, treat it as a re-auth signal — the manager refreshes or re-obtains; if that fails, remove the authorized client and re-initiate the flow. | [paraphrase] | servlet/oauth2/client/authorized-clients.html
- MUST | Decode backend errors as RFC 9457 Problem Details (`application/problem+json`) — read `type`/`title`/`status`/`detail`/`instance`. | [paraphrase] | rfc-editor.org/rfc/rfc9457

## 2. CORS (backend-facing requirement)

- MUST | The backend must allow the client's origin via CORS; browser clients require explicit backend CORS config (or no CORS headers → browser rejects). | [quote] | web/webmvc-cors.html (cross-ref)
- ATTEND | With credentials, the backend must use `allowOriginPatterns`/finite origins — not `*` — for authorized cross-origin calls. | [paraphrase] | web/webmvc-cors.html (cross-ref)

## 3. Contract & versioning

- ATTEND | Follow the backend's API versioning (header/path/media-type) when calling versioned endpoints; un-versioned endpoints have lowest priority. | [paraphrase] | 02-web.md §5 (cross-ref)
- MUST | Validate the backend's API response types/status codes against the documented contract; treat unexpected problem-detail extensions as machine-readable context, not errors. | [paraphrase] | rfc-editor.org/rfc/rfc9457

## 4. Client HTTP tooling (official)

- MUST | Prefer Spring's `RestClient` (with `OAuth2ClientHttpRequestInterceptor`) or `WebClient` (with `ServletOAuth2AuthorizedClientExchangeFilterFunction`) for protected calls — both integrate the manager. | [quote] | servlet/oauth2/client/authorized-clients.html