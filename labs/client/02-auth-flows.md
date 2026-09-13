# 02 — Auth Flows: Authorization Code + PKCE / Client Credentials / Refresh / Logout / OIDC (isolated client reference)

> **Tree refs:** 3.4 (OAuth2 Client grants) + RFC 6749 + RFC 7519 + RFC 8252 + OIDC.
> **Source:** `https://docs.spring.io/spring-security/reference/servlet/oauth2/client/` (7.1.1) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

## 1. Authorization Code flow (primary)

- MUST | Initiate the flow via `OAuth2AuthorizationRequestRedirectFilter` at `/oauth2/authorization/{registrationId}` (default resolver path). | [quote] | servlet/oauth2/client/authorization-grants.html
- ATTEND | The `AuthorizationRequestRepository` persists the `OAuth2AuthorizationRequest` between request and callback (default: `HttpSessionOAuth2AuthorizationRequestRepository`) — used to correlate/validate the response. | [paraphrase] | servlet/oauth2/client/authorization-grants.html
- MUST | Exchange the code for a token at the Token Endpoint via `OAuth2AccessTokenResponseClient` (default `RestClientAuthorizationCodeTokenResponseClient`). | [paraphrase] | servlet/oauth2/client/authorization-grants.html
- ATTEND | `redirect-uri` supports URI template vars (`{baseUrl}`, `{baseScheme}://{baseHost}{basePort}{basePath}`, `{registrationId}`) — important behind a proxy (uses `X-Forwarded-*`). | [quote] | servlet/oauth2/client/authorization-grants.html
- MUST | Validate the redirect URI with exact string matching against pre-registered URIs (SAS side). | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html

## 2. Client Credentials (M2M)

- MUST | Obtain the token directly from the Token Endpoint (no user redirect); the interceptor/manager does this automatically. | [quote] | servlet/oauth2/client/authorization-grants.html · servlet/oauth2/client/authorized-clients.html
- ATTEND | Authorize the manager to support `client_credentials` via `OAuth2AuthorizedClientProviderBuilder` (composite with authorizationCode + refreshToken + clientCredentials). | [paraphrase] | servlet/oauth2/client/index.html

## 3. Refresh flow

- MUST | Enable `refresh_token` in the `OAuth2AuthorizedClientProvider` composite so an expired token is renewed. | [quote] | servlet/oauth2/client/authorized-clients.html
- ATTEND | Renewal happens only when a provider is available to perform the refresh; configure it explicitly. | [paraphrase] | servlet/oauth2/client/authorized-clients.html
- ATTEND | The client filter does NOT auto-renew in the resource-server role (that is server-side; here the client manager does). | [paraphrase] | servlet/oauth2/resource-server/bearer-tokens.html

## 4. Logout / OIDC

- ATTEND | For OIDC, logout may include a Provider-side logout (`OIDC Logout`); confirm the end-session endpoint semantics with the provider. | [paraphrase] | servlet/oauth2/authorization-server/configuration-model.html
- MUST | On logout, revoke the token and clear the authorized client (per OAuth2 revocation) so it cannot be reused. | [paraphrase] | servlet/oauth2/authorized-clients.html · rfc-editor.org/rfc/rfc7009

## 5. Failure handling

- MUST | On an invalid/expired token, remove the `OAuth2AuthorizedClient` so it is not used again — via `OAuth2AuthorizationFailureHandler` (with `OAuth2AuthorizedClientRepository` or `OAuth2AuthorizedClientService`). | [quote] | servlet/oauth2/client/authorized-clients.html