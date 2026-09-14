# 03 — Token Handling & Secure Storage (isolated client reference)

> **Tree refs:** 3.4 (OAuth2 Client authorized clients) + OWASP SPA cheat sheet.
> **Source:** `https://docs.spring.io/spring-security/reference/servlet/oauth2/client/` (7.1.1) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

## 1. Obtaining & resolving the authorized client

- MUST | Resolve the client for a request via `@RegisteredOAuth2AuthorizedClient(registrationId)` (handled by `OAuth2AuthorizedClientArgumentResolver`, inheriting the manager's capabilities). | [quote] | servlet/oauth2/client/authorized-clients.html
- ATTEND | The `OAuth2AuthorizedClientManager` collaborates with one or more `OAuth2AuthorizedClientProvider`s; it obtains a token if none exists, and refreshes/renews an expired one. | [paraphrase] | servlet/oauth2/client/authorized-clients.html

## 2. Transporting the token to the backend

- MUST | Send the access token as a **Bearer** in the `Authorization` header via `OAuth2ClientHttpRequestInterceptor` (RestClient) or `ServletOAuth2AuthorizedClientExchangeFilterFunction` (WebClient). | [quote] | servlet/oauth2/client/authorized-clients.html
- ATTEND | The interceptor uses a `ClientRegistrationIdResolver` (default from request attributes) and a `PrincipalResolver` (default from `SecurityContextHolder`) to scope the stored authorized client. | [paraphrase] | servlet/oauth2/client/authorized-clients.html
- AVOID | The WebClient default-client shortcuts (`setDefaultOAuth2AuthorizedClient(true)` / `setDefaultClientRegistrationId(...)`) — be cautious, every request gets the token. | [quote] | servlet/oauth2/client/authorized-clients.html

## 3. Secure storage (BFF pattern)

- MUST | Never store tokens in the browser (no localStorage/sessionStorage for access/refresh tokens); keep them server-side. | [paraphrase] | OWASP SPA cheat sheet
- MUST | Use the **Backend-for-Frontend (BFF)** pattern for SPA/mobile: the browser talks to its own backend, which holds the client secret and performs the OAuth2 dance; the token never reaches the user agent. | [paraphrase] | OWASP SPA cheat sheet · 01-client-types.md §2
- MUST | Protect the token storage at rest (server session/secure store) and the client secret via env/secret-manager. | [paraphrase] | OWASP secrets management cheat sheet

## 4. Revocation & failure

- MUST | On logout/compromise, revoke the token (RFC 7009) and clear the authorized client. | [paraphrase] | servlet/oauth2/client/authorized-clients.html · rfc-editor.org/rfc/rfc7009
- MUST | On an invalid token, remove the `OAuth2AuthorizedClient` via `OAuth2AuthorizationFailureHandler` so it is not reused. | [quote] | servlet/oauth2/client/authorized-clients.html