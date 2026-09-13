# 03 — Spring Security 7.1.1 + Authorization Server (isolated official reference)

> **Tree refs:** 3.1 (Servlet Architecture), 3.2 (Auth partial), 3.3 (Authorization), 3.4 (OAuth2 Resource Server), 3.6 (SAS), 3.7 (Exploits), 3.9 (Integrations/Testing).
> **Source:** `https://docs.spring.io/spring-security/reference/` @ 7.1.1 — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. `[quote]`=verbatim · `[paraphrase]`=faithful restatement.
> **Isolation:** no internal repo data, no current system. **Encoding:** UTF-8.

---

## 1. Servlet Architecture (3.1)

- MUST | Spring Security servlet support is built on Servlet Filters; the order in which filters run is extremely important. | [quote] | servlet/architecture.html#servlet-filters-review
- ATTEND | `FilterChainProxy` is the central filter; it clears the `SecurityContext` (avoiding leaks) and applies `HttpFirewall`. | [paraphrase] | servlet/architecture.html#servlet-filterchainproxy
- MUST | Order multiple `SecurityFilterChain`s by specificity — ONLY the first matching chain is invoked; use `@Order` (smaller = evaluated first). | [quote] | servlet/architecture.html#servlet-securityfilterchain
- ATTEND | Filter execution order: CsrfFilter → authentication filters → AuthorizationFilter (last by default). | [quote] | servlet/architecture.html#servlet-security-filters
- AVOID | Declaring a filter as a plain Spring bean — Spring Boot may register it twice (container + Security) in different order; disable via `FilterRegistrationBean(enabled=false)`. | [paraphrase] | servlet/architecture.html#servlet-security-filters
- ATTEND | `ExceptionTranslationFilter` maps `AccessDeniedException`/`AuthenticationException` to HTTP; on auth failure it saves the request to a `RequestCache` and asks for credentials. | [paraphrase] | servlet/architecture.html#servlet-exceptiontranslationfilter
- ATTEND | Use `NullRequestCache` for stateless REST to prevent saving requests in the session. | [paraphrase] | servlet/architecture.html#savedrequests
- AVOID | Relying on 401/403 response bodies for detail — Security intentionally leaves them empty; read DEBUG/TRACE logs instead. | [quote] | servlet/architecture.html#servlet-logging

## 2. Authentication (3.2 partial)

- MUST | Store passwords with adaptive one-way functions (bcrypt/PBKDF2/scrypt/argon2); tune work factor to ~1 second verify. | [quote] | features/authentication/password-storage.html
- MUST | Use `DelegatingPasswordEncoder` with the `{id}encodedPassword` format; missing `{id}` → `IllegalArgumentException: no PasswordEncoder mapped for id "null"`. | [quote] | features/authentication/password-storage.html#authentication-password-storage-dpe
- AVOID | `User.withDefaultPasswordEncoder()` in production — "unsafe for production", sample apps only. | [quote] | servlet/authentication/passwords/index.html
- ATTEND | `DaoAuthenticationProvider` flow: auth filter → token → ProviderManager → UserDetailsService lookup → PasswordEncoder verify. | [paraphrase] | servlet/authentication/passwords/dao-authentication-provider.html
- ATTEND | Spring Security 6+: only one of `SecurityContextHolderFilter`/`SecurityContextPersistenceFilter` should be set; users must explicitly save the `SecurityContext` via `SecurityContextRepository` (`requireExplicitSave`). | [paraphrase] | servlet/authentication/session-management.html
- MUST | Use `SessionCreationPolicy.STATELESS` for stateless REST — configures `NullSecurityContextRepository`, prevents saving requests. | [paraphrase] | servlet/authentication/session-management.html
- ATTEND | Session-fixation protection is automatic on login (changeSessionId default); setting it to `none` is not recommended. | [paraphrase] | servlet/authentication/session-management.html
- ATTEND | Default `/logout` responds to GET and POST; failing to call `SecurityContextLogoutHandler` means the user is not actually logged out. | [paraphrase] | servlet/authentication/logout.html
- ATTEND | Auth events: an `AuthenticationEventPublisher` must be published first; event matching is an exact Exception match (subclasses do NOT fire). | [quote] | servlet/authentication/events.html

## 3. Authorization (3.3)

- MUST | With any `HttpSecurity`, declare `.anyRequest().authenticated()` as a coarse catch-all net. | [quote] | servlet/authorization/authorize-http-requests.html
- ATTEND | `AuthorizationFilter` runs on EVERY dispatch (REQUEST/FORWARD/ERROR/INCLUDE) — often need `.dispatcherTypeMatchers(FORWARD, ERROR).permitAll()`. | [paraphrase] | servlet/authorization/authorize-http-requests.html
- MUST | Order authorization rules; only the FIRST match applies; prefer `anyRequest().denyAll()` as an allow-list default. | [paraphrase] | servlet/authorization/authorize-http-requests.html
- MUST | Prefer `permitAll` over `ignoring` — `ignoring` skips secure headers; Security writes headers on permitted requests. | [quote] | servlet/authorization/authorize-http-requests.html
- MUST | Enable method security explicitly with `@EnableMethodSecurity` — "Spring Boot Starter Security does not activate method-level authorization by default". | [quote] | servlet/authorization/method-security.html
- AVOID | Relying on method-security alone — unannotated methods are NOT secured; keep a catch-all in HttpSecurity. | [paraphrase] | servlet/authorization/method-security.html
- MUST | Use defense in depth: URL-level (coarse) + method-level (fine) + object-level together. | [paraphrase] | servlet/exploits/firewall.html · servlet/authorization/method-security.html
- MUST | Check object-level authorization on read with `@PostAuthorize` (IDOR); do NOT use it on DB-write methods (a write happens before the check). | [quote] | servlet/authorization/method-security.html
- MUST | Prefer granting authorities over complicated SpEL expressions. | [quote] | servlet/authorization/method-security.html
- ATTEND | Multiple method-security annotations are ANDed; repeating the same annotation on one method is not supported. | [paraphrase] | servlet/authorization/method-security.html
- ATTEND | Method security is AOP-proxy based — self-invocation (`this.x()`) does NOT pass the security proxy; call through an external invocation. | [paraphrase] | servlet/authorization/method-security.html
- ATTEND | `AuthorizationDeniedEvent` fires on denial; `AuthorizationGrantedEvent` is noisy and not published by default. | [quote] | servlet/authorization/events.html
- MUST | Use a deny-by-default strategy with a catch-all `/**` denied last. | [quote] | servlet/exploits/firewall.html

## 4. OAuth2 Resource Server — JWT (3.4)

- ATTEND | Resource Server supports two bearer forms: JWT and Opaque tokens. | [quote] | servlet/oauth2/resource-server/index.html
- MUST | Default `NimbusJwtDecoder` trusts RS256 only. | [quote] | servlet/oauth2/resource-server/jwt.html
- MUST | Runtime validation: verify signature against public keys (jwks), validate `exp`/`nbf`/`iss` (default), and map scopes to `SCOPE_`-prefixed authorities. | [paraphrase] | servlet/oauth2/resource-server/jwt.html
- MUST | Validate `aud` — via Boot `audiences` property or a `DelegatingOAuth2TokenValidator`; validation fails if `iss`/`aud` don't match. | [paraphrase] | servlet/oauth2/resource-server/jwt.html
- MUST | When building `NimbusJwtDecoder` manually, set `JwtValidators.createDefaultWithIssuer(issuer)`. | [paraphrase] | servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-decoder-bean
- ATTEND | Default clock skew = 60 seconds (`JwtTimestampValidator`); `jti` coerced to String (token tracking/replay). | [paraphrase] | servlet/oauth2/resource-server/jwt.html
- ATTEND | Resource Server makes NO attempt to renew expired tokens (unlike the client filter). | [quote] | servlet/oauth2/resource-server/bearer-tokens.html
- AVOID | Skipping `iss`/`aud` validation — add via `DelegatingOAuth2TokenValidator` or the `audiences` property. | [paraphrase] | servlet/oauth2/resource-server/jwt.html

## 5. OAuth2 Authorization Server — SAS (3.6)

- MUST | Provide `RegisteredClientRepository` and `AuthorizationServerSettings` — both are REQUIRED. | [quote] | servlet/oauth2/authorization-server/configuration-model.html · core-model-components.html
- MUST | Encode client secrets with `PasswordEncoder`; PKCE is enabled by default for Authorization Code clients. | [paraphrase] | servlet/oauth2/authorization-server/core-model-components.html
- ATTEND | Default endpoints: authorize, token, introspection, revocation, metadata, JWK Set (JWK only if a `JWKSource` bean is registered); Device/Client-Registration disabled by default; OIDC disabled by default. | [paraphrase] | servlet/oauth2/authorization-server/configuration-model.html
- MUST | Configure a user-authentication mechanism for the `authorization_code` grant — the resource owner must be authenticated. | [quote] | servlet/oauth2/authorization-server/configuration-model.html
- ATTEND | Client authentication is required on Token/Introspection/Revocation endpoints; `JwtClientAssertionDecoderFactory` validates iss/sub/aud/exp/nbf. | [paraphrase] | servlet/oauth2/authorization-server/configuration-model.html
- MUST | Use exact string matching when comparing client redirect URIs against pre-registered URIs. | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html
- ATTEND | Grant types: authorization_code, refresh_token, client_credentials, device_code, token-exchange. | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html
- ATTEND | `OAuth2TokenGenerator`: SELF_CONTAINED (default) → JWT; REFERENCE → opaque token; tokens become inactive when expired or revoked. | [paraphrase] | servlet/oauth2/authorization-server/protocol-endpoints.html
- ATTEND | DPoP (RFC 9449) binds an access token to a public key and requires proof of possession — in contrast to a plain bearer token. | [quote] | servlet/oauth2/authorization-server/protocol-endpoints.html

## 6. Protection Against Exploits (3.7)

- MUST | CSRF is enabled by default for unsafe methods (POST/PUT/PATCH/DELETE); do not disable for browser-serving APIs. | [quote] | servlet/exploits/csrf.html
- ATTEND | A backend that does NOT serve browser traffic may disable CSRF (`.csrf(csrf -> csrf.disable())`); else keep it. | [quote] | servlet/exploits/csrf.html#disable-csrf
- ATTEND | CSRF tokens are deferred and XOR-randomized per request (BREACH protection). | [paraphrase] | servlet/exploits/csrf.html
- MUST | Rely on `StrictHttpFirewall` (default): rejects un-normalized/malicious requests, strips path params/duplicate slashes, blocks HTTP Response Splitting. | [paraphrase] | servlet/exploits/firewall.html
- ATTEND | `StrictHttpFirewall` rejects empty HTTP method — `new MockHttpServletRequest()` fails; use `new MockHttpServletRequest("GET", "")`. | [paraphrase] | servlet/exploits/firewall.html
- MUST | Security response headers are written by `HeaderWriterFilter` — another reason to use `permitAll` over `ignoring`. | [paraphrase] | servlet/exploits/headers.html

## 7. Integrations (3.9 partial)

- MUST | Handle CORS before Spring Security — preflight carries no cookies; configure via `UrlBasedCorsConfigurationSource` or Spring MVC CORS (`.cors(withDefaults())`). | [quote] | servlet/integrations/cors.html
- AVOID | Setting both `configurationSource` and `preFlightRequestHandler` on one CorsConfigurer — startup error. | [paraphrase] | servlet/integrations/cors.html
- ATTEND | Security uses Micrometer for Observability; DEBUG/TRACE logging covers security events. | [paraphrase] | servlet/integrations/observability.html
- ATTEND | Use `DelegatingSecurityContextRunnable`/`Executor` to propagate the `SecurityContext` across threads (Concurrency). | [paraphrase] | servlet/integrations/concurrency.html
- ATTEND | `@PostFilter`/`@PreFilter` in-memory filtering can be expensive — consider filtering in the data layer instead. | [paraphrase] | servlet/integrations/data.html

## 8. Testing (3.9)

- MUST | Use Spring Security's `RequestPostProcessor` static imports: `import static ...SecurityMockMvcRequestPostProcessors.*;`. | [quote] | servlet/test/mockmvc/request-post-processors.html
- MUST | Associate MockMvc with the Security context via `apply(springSecurity())` so `@WithMockUser`/`user(...)` take effect. | [paraphrase] | servlet/test/mockmvc/request-post-processors.html
- MUST | Use `.with(csrf())` on POST requests in tests; mock users/CSRF/form-login/http-basic/oauth2 via `user(...)`, `csrf()`, `formLogin()`, `httpBasic(...)`, `oauth2Login()`/`jwt()`/`opaqueToken()`. | [paraphrase] | servlet/test/mockmvc/request-post-processors.html
- MUST | Test method security with `@WithMockUser(roles=...)` / `@WithUserDetails` / `@WithAnonymousUser`. | [paraphrase] | servlet/test/method.html

---

## Cross-cutting gap insertions (from the standards-miner)

| Gap | Home | Rule added |
|---|---|---|
| Defense in depth (method+URL+object) | §3 | URL + method + object-level authorization used together. |
| `@PreAuthorize`/method-security via proxy (no self-invocation) | §3 | Method security is AOP-proxy based; call externally. |
| `alg: none` / weak HMACs | §4/5 + 09-api-web | Choose `alg` with documented decision; avoid `none`. |
| Sign-then-encrypt | §5 + 09-api-web | Use JWE only when both integrity and confidentiality needed (verify per official JWE page). |

## Verification note
- All pages fetched live 2026-09-12 at Security 7.1.1; URLs exact. `#...` anchors verified within their pages.
- Tree coverage: 3.1 ✅ · 3.2 ◐ (auth subset) · 3.3 ✅ · 3.4 ✅ (JWT) · 3.5 ⭕ SAML · 3.6 ✅ · 3.7 ✅ · 3.8 ◐ (Java config) · 3.9 ◐ · 3.10 ⭕ · 3.11 ⭕ reactive.