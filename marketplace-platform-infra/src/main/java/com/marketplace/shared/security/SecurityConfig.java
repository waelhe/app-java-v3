package com.marketplace.shared.security;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.config.MarketplaceProperties;
import com.marketplace.shared.api.ApiProblemDetails;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(proxyTargetClass = true)
public class SecurityConfig {

    /**
     * Production profile gate, mirroring {@link OAuth2ClientSecretInitializer}'s
     * established fail-fast pattern (profile-gated, never a global fail-fast that
     * would break dev/CI).
     */
    private static final Profiles PROD_PROFILE = Profiles.of("prod");

    private final MarketplaceProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(MarketplaceProperties properties,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                )
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http,
                                                          CorrelationIdFilter correlationIdFilter) throws Exception {
        http
                .securityMatcher("/api/**", "/actuator/**", "/graphql", "/v3/api-docs/**")
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**", "/graphql", "/v3/api-docs/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/listings/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reviews/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                        // L30 (realestate systems plan): the administrative
                        // hierarchy is public reference data — the anonymous
                        // browse pattern, one line, same chain.
                        .requestMatchers(HttpMethod.GET, "/api/v1/geo/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs").permitAll()
                        .requestMatchers("/api/v1/payments/webhooks/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemDetailAuthenticationEntryPoint())
                        .accessDeniedHandler(problemDetailAccessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // Route the bearer-filter failure path (an invalid supplied
                        // token arrives as InvalidBearerTokenException) through the
                        // same problem-detail entry point as the anonymous path, so
                        // every 401 carries the RFC 7807 body + RFC 6750 challenge.
                        .authenticationEntryPoint(problemDetailAuthenticationEntryPoint()));

        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
                                                   SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/assets/**", "/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .maximumSessions(properties.security().session().maxSessions())
                        .sessionRegistry(sessionRegistry))
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
            FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        return new SpringSessionBackedSessionRegistry<>(sessionRepository);
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * CORS policy for the shared chains: credentials enabled, one-hour
     * preflight cache and the request headers first-party clients send —
     * including {@code X-API-Version}, consumed by
     * {@code ApiVersioningConfig#useRequestHeader} (A2).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-API-Version must be allowed so versioned clients can send the
        // X-API-Version request header consumed by ApiVersioningConfig
        // (ApiVersionConfigurer#useRequestHeader) across the same origin (A2).
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "Idempotency-Key", "X-API-Version"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * The 401 handler for the resource-server chain: emits the RFC 7807
     * problem body plus the RFC 6750 §3 {@code WWW-Authenticate} Bearer
     * challenge (A3).
     *
     * <p>Challenge semantics mirror the framework's own
     * {@code BearerTokenAuthenticationEntryPoint} (Spring Security 7):
     * a request that <em>lacked</em> credentials — arriving from
     * {@code ExceptionTranslationFilter} as
     * {@code InsufficientAuthenticationException} — gets the bare
     * {@code Bearer realm="marketplace"} challenge, per RFC 6750 §3.1
     * ("If the request lacks any authentication information ... the resource
     * server SHOULD NOT include an error code or other error information");
     * a <em>supplied</em> token that failed validation — arriving from the
     * resource-server bearer filter as {@code InvalidBearerTokenException}, an
     * {@link OAuth2AuthenticationException} wrapping
     * {@code BearerTokenErrors.invalidToken} — gets
     * {@code error="invalid_token"} (plus {@code error_description}) per
     * RFC 6750 §3 ("If the protected resource request included an access token
     * and failed authentication, the resource server SHOULD include the
     * 'error' attribute").
     *
     * <p>Registered on both official seams so all 401s share one contract:
     * {@code .exceptionHandling().authenticationEntryPoint(...)} (the
     * anonymous/translation path) and
     * {@code .oauth2ResourceServer().authenticationEntryPoint(...)} (the
     * bearer filter failure path — OAuth2ResourceServerConfigurer wires that
     * entry point into {@code BearerTokenAuthenticationFilter}).
     *
     * @return the problem-detail authentication entry point
     */
    @Bean
    AuthenticationEntryPoint problemDetailAuthenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(ex));
            writeProblemDetail(
                    response,
                    ApiErrorTaxonomy.AUTHN,
                    "Authentication required",
                    request
            );
        };
    }

    /**
     * Computes the RFC 6750 §3 Bearer challenge for an authentication failure,
     * mirroring {@code BearerTokenAuthenticationEntryPoint} (Spring Security 7):
     * error attributes only when the exception reports a failed <em>supplied</em>
     * token ({@link OAuth2AuthenticationException} carrying the framework's
     * {@code BearerTokenErrors} details), otherwise the bare realm challenge.
     *
     * @param ex the authentication failure reported by the filter chain
     * @return the {@code WWW-Authenticate} header value
     */
    private static String bearerChallenge(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauth2) {
            OAuth2Error error = oauth2.getError();
            if (error.getDescription() != null && !error.getDescription().isBlank()) {
                return "Bearer realm=\"marketplace\", error=\"" + error.getErrorCode()
                        + "\", error_description=\"" + error.getDescription() + "\"";
            }
            return "Bearer realm=\"marketplace\", error=\"" + error.getErrorCode() + "\"";
        }
        return "Bearer realm=\"marketplace\"";
    }

    /**
     * The 403 handler: an authenticated request that lacks sufficient
     * privileges is challenged with {@code error="insufficient_scope"}
     * (RFC 6750 §3.1) alongside the problem body (A3).
     *
     * @return the problem-detail access-denied handler
     */
    @Bean
    AccessDeniedHandler problemDetailAccessDeniedHandler() {
        return (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer realm=\"marketplace\", error=\"insufficient_scope\"");
            writeProblemDetail(
                    response,
                    ApiErrorTaxonomy.AUTHZ,
                    "Access denied",
                    request
            );
        };
    }

    private void writeProblemDetail(HttpServletResponse response,
                                    ApiErrorTaxonomy taxonomy,
                                    String detail,
                                    HttpServletRequest request) throws IOException {
        String traceId = response.getHeader(CorrelationIdFilter.HEADER_NAME);

        var problemDetail = ApiProblemDetails.fromTaxonomy(taxonomy, detail, request.getRequestURI(), null, traceId);

        response.setStatus(taxonomy.statusCode().value());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }

    @Bean
    UserDetailsManager userDetailsService(DataSource dataSource) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        manager.setUsersByUsernameQuery("select username, password, enabled from auth_users where username = ?");
        manager.setAuthoritiesByUsernameQuery("select username, authority from auth_authorities where username = ?");
        manager.setCreateUserSql("insert into auth_users (username, password, enabled) values (?, ?, ?)");
        manager.setUpdateUserSql("update auth_users set password = ?, enabled = ? where username = ?");
        manager.setDeleteUserSql("delete from auth_users where username = ?");
        manager.setCreateAuthoritySql("insert into auth_authorities (username, authority) values (?, ?)");
        manager.setDeleteUserAuthoritiesSql("delete from auth_authorities where username = ?");
        manager.setUserExistsSql("select count(*) from auth_users where username = ?");
        return manager;
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                    RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                  RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * Signing keys: a persistent JKS keystore delivered through one of two source
     * channels, or — <em>only outside the {@code prod} profile</em> — an ephemeral RSA
     * key generated on startup.
     *
     * <p><b>Source channels (b64 wins when both are present):</b></p>
     * <ul>
     *   <li><b>{@code b64}</b> — base64-encoded JKS bytes bound from
     *       {@code JWT_KEYSTORE_B64} to
     *       {@code marketplace.security.jwt.keystore.b64}. The application-level
     *       channel for platforms that deliver secrets as write-only environment
     *       variables (Railway: values are not readable through the API, volumes
     *       mount as root so a non-root container cannot write them, and pre-deploy
     *       filesystem changes do not persist — docs.railway.com/volumes,
     *       /deployments/pre-deploy-command). Decoded and loaded in memory via
     *       {@link KeyStore#load(InputStream, char[])}: no file is ever
     *       materialized, so the container entrypoint stays the pure official
     *       Spring Boot recipe {@code ENTRYPOINT ["java", "-jar", "app.jar"]}.
     *       Key rotation is a variable update ({@code keys/README.md} §4).</li>
     *   <li><b>{@code path}</b> — {@code file:}/{@code classpath:} location per the
     *       runbook {@code keys/README.md} §2 (development hosts, mounted files).</li>
     * </ul>
     *
     * <p>Ephemeral generation is the official quickstart pattern (Spring Authorization
     * Server — Getting Started: "This is a minimal configuration for getting started
     * quickly"; its {@code generateRsaKey()} helper is "an instance of KeyPair with
     * keys generated on startup"). It is fine for dev/test, but in production it would
     * silently invalidate every issued token on each restart (and break multi-instance
     * deployments), which the governing plan forbids — {@code auth-system-redesign-plan}
     * INV-7: "لا مفاتيح توقيع عابرة في غير بيئة التطوير", risk register mitigation:
     * "فحص CI يحظر {@code JWT_KEYSTORE_*} الفارغة في الإنتاج".
     *
     * <p>Hence this defense-in-depth gate, mirroring the initializer's profile-gated
     * fail-fast:
     * <ul>
     *   <li>{@code prod} active + <b>both</b> sources blank ⇒ startup fails loudly
     *       instead of falling back to an ephemeral key. {@code application-prod.yml}
     *       binds the credential fields with no defaults (placeholder resolution
     *       fails when unset); this bean-level guard also catches blank-string
     *       values and any future binding drift.</li>
     *   <li>A source present with incomplete credentials ({@code password},
     *       {@code alias}, {@code keyPassword}) fails in <b>every</b> profile — a
     *       half-configured keystore is a misconfiguration, never an intentional
     *       quickstart, and must not silently degrade to an ephemeral key.</li>
     * </ul>
     * Enforced in CI by {@code JwkSourceProdHardeningTest}.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(
            ResourceLoader resourceLoader,
            Environment environment
    ) throws Exception {
        var ks = properties.security().jwt().keystore();
        String keyStoreB64 = ks.b64();
        String keyStorePath = ks.path();
        String keyStorePassword = ks.password();
        String keyAlias = ks.alias();
        String keyPassword = ks.keyPassword();
        boolean hasB64 = isNotBlank(keyStoreB64);
        boolean hasPath = isNotBlank(keyStorePath);
        if (!hasB64 && !hasPath) {
            if (environment.acceptsProfiles(PROD_PROFILE)) {
                throw new IllegalStateException(
                        "marketplace.security.jwt.keystore.b64 (JWT_KEYSTORE_B64) or .path (JWT_KEYSTORE_PATH)"
                                + " must be configured in production (plus password/alias/keyPassword) —"
                                + " ephemeral signing keys are forbidden outside development"
                                + " (auth-system-redesign-plan INV-7)");
            }
            KeyPair keyPair = generateRsaKey();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }
        if (isBlank(keyStorePassword) || isBlank(keyAlias) || isBlank(keyPassword)) {
            throw new IllegalStateException(
                    "marketplace.security.jwt.keystore.password/.alias/.keyPassword are required whenever a"
                            + " keystore source (" + (hasB64 ? "b64" : "path") + ") is configured — a"
                            + " half-configured keystore never falls back to an ephemeral key");
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        if (hasB64) {
            // Write-only secret platforms deliver the JKS as a base64 variable; decoding
            // here keeps the container entrypoint pure (no shell materialization).
            // Whitespace is stripped to stay compatible with tolerant base64 decoders
            // (line-wrapped values) — same behavior the entrypoint decode provided.
            byte[] keystoreBytes = Base64.getDecoder().decode(keyStoreB64.replaceAll("\\s", ""));
            keyStore.load(new ByteArrayInputStream(keystoreBytes), keyStorePassword.toCharArray());
        }
        else {
            String resolvedLocation = keyStorePath.startsWith("classpath:") || keyStorePath.startsWith("file:")
                    ? keyStorePath
                    : "file:" + keyStorePath;

            try (InputStream inputStream = resourceLoader.getResource(resolvedLocation).getInputStream()) {
                keyStore.load(inputStream, keyStorePassword.toCharArray());
            }
        }

        RSAPublicKey publicKey = (RSAPublicKey) keyStore.getCertificate(keyAlias).getPublicKey();
        Key privateKeyCandidate = keyStore.getKey(keyAlias, keyPassword.toCharArray());
        RSAPrivateKey privateKey = (RSAPrivateKey) Objects.requireNonNull(privateKeyCandidate,
                () -> "No private key found in keystore for alias " + keyAlias);

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyAlias)
                .build();

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /**
     * Decodes and validates access tokens signed by the co-located
     * authorization server.
     *
     * <p>Reference: Spring Security — OAuth 2.0 Resource Server JWT:
     * "Or, exposing a JwtDecoder @Bean has the same effect as decoder()...
     * NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();"
     * https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
     *
     * @param jwkSource the shared JWK source backed by the authorization server's signing keys
     * @return the decoder validating {@code iss}, {@code aud} and signature
     */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource,
                          AuthorizationServerSettings authorizationServerSettings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(authorizationServerSettings.getIssuer()),
                requiredAudiencesValidator(List.of(properties.security().jwt().audience()))));
        return decoder;
    }

    /**
     * Enforces the JWT audience contract per RFC 7519 §4.1.3:
     * <blockquote>
     * "The 'aud' (audience) claim identifies the recipients that the JWT is
     * intended for. Each principal intended to process the JWT MUST identify
     * itself with a value in the audience claim. If the principal processing
     * the claim does not identify itself with a value in the 'aud' claim when
     * this claim is present, then the JWT MUST be rejected."
     * </blockquote>
     *
     * <p>The spec's check is "the principal finds ITS OWN identifier among
     * {@code aud}" — for a single configured audience ({@code marketplace-api})
     * {@code anyMatch} is exactly {@code contains}, and it stays the correct
     * form if the configured list ever grows (each token is accepted when it
     * names this resource server among its recipients). A strict
     * {@code allMatch} would deviate from the RFC by demanding the token name
     * every configured audience. (codex-review-fixes-plan A8: document, do not
     * change.)
     */
    private static OAuth2TokenValidator<Jwt> requiredAudiencesValidator(List<String> audiences) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(audiences::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The required audience is missing",
                null
        ));
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter correlationIdFilter) {
        FilterRegistrationBean<CorrelationIdFilter> registrationBean = new FilterRegistrationBean<>(correlationIdFilter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /**
     * Customizes every access token issued by Spring Authorization Server:
     * <ul>
     *   <li>{@code roles} — flattened authorities (e.g. "ADMIN") per
     *       <a href="https://docs.spring.io/spring-authorization-server/reference/guides/how-to-custom-claims-authorities.html">
     *       How-to: Customize JWT Claims</a>.</li>
     *   <li>{@code aud} — the resource server audience per
     *       <a href="https://datatracker.ietf.org/doc/html/rfc7519#section-4.1.3">RFC 7519 §4.1.3</a>.</li>
     * </ul>
     *
     * <p>The {@code aud} claim is set with a mutable {@code ArrayList} on purpose:
     * {@code JwtClaimsSet.Builder.audience()} stores the given list as-is
     * (spring-security-oauth2-jose, JwtClaimsSet.java:113-115), and the JDBC-backed
     * {@code JdbcOAuth2AuthorizationService} serializes the token claims with
     * default typing enabled. An immutable {@code List.of(...)}
     * ({@code java.util.ImmutableCollections$List12}) is denied by the security
     * Jackson {@code PolymorphicTypeValidator} on deserialization, which breaks the
     * {@code refresh_token} grant - the authorization metadata can no longer be
     * read back. A standard {@code ArrayList} round-trips, exactly like the
     * framework's own claim collections.
     *
     * @return the token customizer bean
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims()
                        .claim("roles", context.getPrincipal().getAuthorities().stream()
                                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                                .map(a -> a.startsWith("ROLE_") ? a.replaceFirst("^ROLE_", "") : a)
                                .collect(java.util.stream.Collectors.toSet()))
                        .audience(new ArrayList<>(List.of(properties.security().jwt().audience())));
            }
        };
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

}
