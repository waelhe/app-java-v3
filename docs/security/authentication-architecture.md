# Authentication Architecture — Official Design Reference

> **Status:** normative reference for the authentication system as implemented on this branch.
> **Pinned versions:** Spring Boot 4.1.1 · Spring Security 7.1.1 (includes the former Spring
> Authorization Server, now versioned and documented as part of Spring Security) ·
> Spring Session 7.x (Redis, indexed) · Java 25 / Maven multi-module.
> **Method:** every decision below is grounded either in the official reference documentation
> for those exact versions or in the framework source code (cited by class and line where the
> documentation is silent). No design element derives from opinion.

---

## 1. Layer model

The authentication system is composed of four layers. Each layer only depends on the layer
above it, and every layer is a documented framework concern — there is no bespoke
infrastructure in between:

```
Layer 0  Build (Maven)            starters, dependency edges, version alignment via Boot BOM
Layer 1  Framework auto-config    what Spring Boot manages by itself (beans + properties)
Layer 2  Security filter chains   the official user extension point (3 chains, ordered)
Layer 3  Extension hooks          documented @Bean customization points (token, session, keys)
```

The design rule applied throughout: **the framework manages everything it can manage; the
application only supplies the pieces the documentation assigns to the application.** Wherever
the application deviates from the textbook defaults, the deviation is recorded in
§7 with its official justification.

## 2. Layer 0 — Build (Maven)

`marketplace-platform-infra/pom.xml` declares exactly the official starters that map to the
features in use (Boot 4 module naming):

| Starter | Provides | Why it is present |
|---|---|---|
| `spring-boot-starter-security` | Spring Security core, servlet integration | Authentication infrastructure, `@EnableMethodSecurity` support |
| `spring-boot-starter-security-oauth2-authorization-server` | Spring Security's authorization server support + Boot auto-config module | Issues tokens (authorization code + PKCE, refresh, client credentials) |
| `spring-boot-starter-security-oauth2-resource-server` | Resource server JWT support + `JwtDecoder` auto-config | Validates the tokens issued by the co-located authorization server |
| `spring-boot-starter-session-data-redis` | Spring Session Redis + Boot session auto-config | Cluster-safe sessions with concurrency control |
| `spring-boot-starter-data-redis` (app module) | Redis driver | Session + cache backend |

`spring-boot-starter-security-oauth2-client` is deliberately **absent**: the application never
acts as an OAuth 2. *client* (its users authenticate locally through form login against the
JDBC `UserDetailsManager`; external clients call the authorization server). Keeping the
dependency absent is the officially correct state — a starter for a feature the application
does not use belongs in neither the compile nor the test classpath, and its former presence
forced 14 `@WebMvcTest` classes to exclude auto-configurations that no longer exist.

## 3. Layer 1 — What the framework manages automatically

### 3.1 The two official operating modes

Boot 4.1 ships a dedicated authorization-server auto-configuration module
(`spring-boot-security-oauth2-authorization-server`). Its web-security part is gated on
`@ConditionalOnDefaultWebSecurity` — i.e. it only activates when the application declares
**no** `SecurityFilterChain` bean of its own. In that mode the framework builds both chains
itself (`OAuth2AuthorizationServerWebSecurityConfiguration`):

- authorization-server chain at `Ordered.HIGHEST_PRECEDENCE`: `oauth2AuthorizationServer`
  DSL (endpoint matcher + `.oidc(withDefaults())`), `anyRequest().authenticated()`,
  `oauth2ResourceServer(jwt)`, login entry point for `text/html`;
- a default chain: `anyRequest().authenticated()` + `formLogin(withDefaults())`.

This application cannot use that mode: it needs behavior the default chains do not carry
(per-path authorization rules, a stateless Bearer API chain, concurrency-controlled
sessions, CORS). Declaring the chains flips the condition off
(`DefaultWebSecurityCondition` = `@ConditionalOnClass({SecurityFilterChain, HttpSecurity})`
+ `@ConditionalOnMissingBean(SecurityFilterChain)`), which is the documented and intended
mechanism: **the application's `SecurityFilterChain` beans replace the defaults and become
the extension point.**

### 3.2 What stays framework-managed even with custom chains

| Concern | Managed by | Evidence |
|---|---|---|
| `AuthorizationServerSettings` (issuer, endpoints) | Boot bean built from `spring.security.oauth2.authorizationserver.*` properties; backs off behind a user bean only via `@ConditionalOnMissingBean` (none declared here) | `OAuth2AuthorizationServerConfiguration` settings bean; `OAuth2AuthorizationServerPropertiesMapper` |
| Token issuance internals (filters, generators, PKCE, endpoint security) | the `oauth2AuthorizationServer` DSL | Spring Security 7.1 reference, Authorization Server “Configuration Model” |
| `JwtDecoder` | Boot auto-config is `@ConditionalOnMissingBean` — inactive here because the application needs a co-located-JWK decoder (see §7) | `JwtDecoderConfiguration` class-level condition |
| Redis session store | `spring-boot-starter-session-data-redis` + `spring.session.data.redis.*` | Spring Session reference (Redis) |
| Users load | Boot wires the `UserDetailsManager` + `PasswordEncoder` beans into the global authentication configuration | Boot “Spring Security” reference |

Note the property namespace asymmetry, verified from source: the authorization-server prefix
is `spring.security.oauth2.authorizationserver` (no dot between `authorization` and `server`),
unlike the resource-server prefix `spring.security.oauth2.resourceserver`.

## 4. Layer 2 — The three security filter chains

`SecurityConfig` declares exactly three chains, mirroring the official pattern at both
levels (the Spring Security 7.1 “Getting Started” sample and Boot's own auto-configured
chain, which use the identical shape):

### 4.1 Chain 1 — authorization server (`@Order(1)`)

```java
http
    .oauth2AuthorizationServer(authorizationServer -> {
        http.securityMatcher(authorizationServer.getEndpointsMatcher());
        authorizationServer.oidc(Customizer.withDefaults());
    })
    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
    .exceptionHandling(exceptions -> exceptions
        .defaultAuthenticationEntryPointFor(
            new LoginUrlAuthenticationEntryPoint("/login"),
            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
    .cors(Customizer.withDefaults());
```

This is line-for-line the documented “Defining Required Components” sample
(securityMatcher inside the lambda, explicit `.oidc(...)`, login entry point for `text/html`),
with two additions the reference itself prescribes elsewhere: `.cors(withDefaults())`
(servlet CORS integration) and nothing else.

**Framework-managed behavior inside this chain (applied by `OAuth2AuthorizationServerConfigurer`
itself, not by this application):**

- CSRF is disabled automatically for the authorization-server endpoint matcher
  (token endpoints must be callable without a session).
- Token-family endpoints (token, introspection, revocation, device, PAR) get an automatic
  `HttpStatusEntryPoint(401)`.
- Because OIDC is enabled and `OidcConfigurer` always registers the user-info endpoint
  (and the client-registration endpoint when configured), the configurer **adds
  `oauth2ResourceServer(jwt(withDefaults()))` to this same chain by itself** so that
  bearer access tokens are accepted on user-info. The application does not (and need not)
  declare it; Boot's own auto-configured chain declares the same line explicitly —
  identical resulting chain either way.
- OIDC is *disabled by default*; the explicit `.oidc(withDefaults())` is the documented way
  to enable OpenID Connect 1.0 (provider configuration, user info, logout endpoints).

### 4.2 Chain 2 — resource server / API (`@Order(2)`)

Stateless Bearer chain for `/api/**`, `/actuator/**`, `/graphql`, `/v3/api-docs/**`:
`STATELESS` session policy, CSRF ignored for the matched paths, per-path authorization
(public GET catalog/reviews/search/health/info/api-docs, public payment webhooks,
`/api/v1/admin/**` requires `ROLE_ADMIN`, everything else authenticated),
`oauth2ResourceServer(jwt)` with a custom authorities converter, and
problem-detail `AuthenticationEntryPoint` / `AccessDeniedHandler` (see §7).

### 4.3 Chain 3 — default / web (`@Order(3)`)

Catch-all for the browser surfaces (login page, static assets): `formLogin(withDefaults())`
against the JDBC `UserDetailsManager`, `permitAll` for `/login` and `/assets/**`, plus
session concurrency control (`maximumSessions` from configuration backed by
`SpringSessionBackedSessionRegistry`) and CORS.

## 5. Layer 3 — Documented extension hooks (beans)

| Bean | Official extension point | Role |
|---|---|---|
| `RegisteredClientRepository` (JDBC) | Authorization Server “Core Model” / Boot note “For production environments, consider using a `JdbcRegisteredClientRepository`” | Client registry backed by `oauth2_registered_client` (schema = PostgreSQL adaptation of the official schema, `V13__authorization_security.sql`) |
| `OAuth2AuthorizationService`, `OAuth2AuthorizationConsentService` (JDBC) | same | Persisted authorizations / consent across restarts |
| `JWKSource` | “Defining Required Components” | Signing keys: production keystore via configuration, generated RSA only as the documented getting-started fallback |
| `JwtDecoder` | Resource Server JWT (exposing a `JwtDecoder` bean has the same effect as `decoder()`) | Validates `iss` + `aud` + signature against the shared `JWKSource` |
| `OAuth2TokenCustomizer<JwtEncodingContext>` | How-to: customize JWT claims/authorities (single bean) | Mints `roles` (authorities without the `ROLE_` prefix) and `aud` = `marketplace-api` into every access token |
| `JwtAuthenticationConverter` | Resource Server JWT authorities mapping | Reads the `roles` claim and re-applies the `ROLE_` prefix for authorization rules |
| `UserDetailsManager` (JDBC, custom schema) | Servlet authentication: JDBC authentication | Form-login principals |
| `PasswordEncoder` (delegating) | Delegating PasswordEncoder | `{bcrypt}` at rest, `{noop}` in fixtures |
| `SpringSessionBackedSessionRegistry` + `HttpSessionEventPublisher` + `spring.session.data.redis.repository-type: indexed` | Spring Session security integration | `maximumSessions` enforcement backed by Redis |
| `CorsConfigurationSource` | Servlet CORS | Allowed origins/methods/headers from configuration |

## 6. Runtime interaction (the loop this design guarantees)

```
client (browser/SPA)                       application
    │  GET /oauth2/authorize?code+PKCE
    ├──────────────────────────────────────► chain 1 (AS) — unauthenticated
    │            302 /login (entry point, text/html)
    ├──────────────────────────────────────► chain 3 (web) — form login, Redis session
    │  POST /login (credentials + CSRF)
    │            302 saved /oauth2/authorize
    ├──────────────────────────────────────► chain 1 (AS) — authenticated principal
    │            302 redirect_uri?code=...&state=...
    │  POST /oauth2/token (basic auth + code_verifier)
    ├──────────────────────────────────────► chain 1 (AS) — token endpoint (CSRF-exempt)
    │            { access_token, refresh_token, id_token }
    │            (OAuth2TokenCustomizer adds roles + aud)
    │  GET /api/v1/...  Authorization: Bearer
    ├──────────────────────────────────────► chain 2 (RS) — JwtDecoder (iss/aud/signature)
    │                                          → JwtAuthenticationConverter (roles → ROLE_)
    │                                          → authorization rules / @PreAuthorize
```

The mint side and the validate side of this loop are contract-bound by three shared
facts, all from configuration: the issuer property
(`spring.security.oauth2.authorizationserver.issuer` → `AuthorizationServerSettings` →
token `iss` claim → decoder issuer validator), the audience property
(`marketplace.security.jwt.audience` → token customizer → decoder audience validator), and
the shared `JWKSource` (token signature → decoder signature validation).

## 7. Deviation ledger (documented, each with its official basis)

| # | Deviation from textbook | Justification |
|---|---|---|
| 1 | Application declares a `JwtDecoder` bean instead of letting Boot build one from `spring.security.oauth2.resourceserver.jwt.*` | AS and RS are co-located: the local `JWKSource` decoder is self-contained (no lazy OIDC discovery HTTP call on first request, no dependency on an externally reachable issuer URL in tests). Boot's auto-decoder is `@ConditionalOnMissingBean` — declaring a bean is the documented takeover. Semantics of the audience validator match Boot's (`!Collections.disjoint`). |
| 2 | Custom problem-detail entry point / access denied handler on chain 2 instead of the default `BearerTokenAuthenticationEntryPoint` | A deliberate, tested API contract (RFC 9457 `application/problem+json` with taxonomy codes) applying to the whole API surface. The authorization-server chain keeps the official entry point pattern. |
| 3 | Three chains instead of one | Documented multi-chain architecture: chains “configured in isolation” with a `securityMatcher`; the AS chain must be highest-precedence so token endpoints are not shadowed. |
| 4 | Generated RSA key when no keystore is configured | The documented getting-started key strategy; production supplies the keystore through configuration (`marketplace.security.jwt.keystore.*`). |

## 8. System-level proof — the real login gate

`AuthorizationServerLoginGateIntegrationTest` closes the E5 gap: `jwt()` test post-processors
bypass the real decoder and converter, so green tests previously did not prove the mint →
validate loop. The gate executes the real flow over real HTTP against a random port:
authorization request with PKCE → login → code → token exchange (`client_secret_basic` +
verifier) → protected API with the Bearer token. It asserts the issued claims
(`iss`, `aud`, `roles`), that an admin token passes the decoder and the role gate, that a
non-admin token is rejected with `403` problem detail, and that the refresh grant rotates a
still-valid access token. Fixtures use the application's own `V13` schema and the
`RegisteredClientRepository` / `UserDetailsManager` beans — the same components production
uses.

## 9. Evidence map

| Claim | Source (version-pinned) |
|---|---|
| Getting-started chain pattern incl. `securityMatcher` in the lambda, `.oidc(withDefaults())`, login entry point for `text/html` | Spring Security 7.1.1 reference — Authorization Server “Getting Started” → “Defining Required Components” |
| Boot builds the AS chain + default chain automatically only in default-security mode | `spring-boot-security-oauth2-authorization-server` 4.1.1 — `OAuth2AuthorizationServerWebSecurityConfiguration` (`@ConditionalOnDefaultWebSecurity`, AS chain at `Ordered.HIGHEST_PRECEDENCE`) |
| `DefaultWebSecurityCondition` = class present + no `SecurityFilterChain` bean | `spring-boot-security` 4.1.1 — `DefaultWebSecurityCondition` |
| Settings from properties, `@ConditionalOnMissingBean` back-off | `OAuth2AuthorizationServerConfiguration` / `OAuth2AuthorizationServerPropertiesMapper` 4.1.1 |
| OIDC disabled by default | `OAuth2AuthorizationServerConfigurer` javadoc 7.1.1 |
| Framework adds `oauth2ResourceServer(jwt)` to the AS chain when OIDC user-info/client-registration endpoints are registered | `OAuth2AuthorizationServerConfigurer.init()` 7.1.1 (also `OidcConfigurer` constructor registering user-info unconditionally) |
| Auto CSRF-ignore for AS endpoints; automatic 401 entry point on token-family endpoints | `OAuth2AuthorizationServerConfigurer.init()` 7.1.1 |
| `JwtDecoder` auto-config backs off behind a bean | `spring-boot-security-oauth2-resource-server` 4.1.1 — `JwtDecoderConfiguration` (class-level `@ConditionalOnMissingBean(JwtDecoder.class)`) |
| Jdbc services for production | Boot 4.1 reference — “OAuth 2.0 Authorization Server” (`spring-security/oauth2` page) |
| Session registry / indexed Redis / `HttpSessionEventPublisher` | Spring Session reference (Redis, Security integration); Spring Security Servlet session management reference |
| Token customizer recipe (`roles` claim) | Spring Security how-to “Customize JWT claims/authorities” |
