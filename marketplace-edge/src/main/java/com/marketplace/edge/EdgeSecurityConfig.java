package com.marketplace.edge;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Edge BFF security. Each element follows an official reference:
 * <ul>
 * <li>Filter chain: the shape of Spring Boot's own default OAuth2 chain
 * ({@code authenticated()}, {@code oauth2Login}, {@code oauth2Client}); a custom
 * chain makes Boot's actuator rules back off, so the health surfaces open with
 * explicit matchers (Boot reference, Actuator "Security" — the
 * {@code GET /actuator/health/**} idiom the main app's own SecurityConfig and
 * the CodeRabbit r1 adoption both use, covering the liveness/readiness
 * subpaths the orchestrator probes).</li>
 * <li>PKCE: the issuer's production client contract is
 * {@code requireProofKey(true)} (the locked RFC 9700 §2.1.1 posture — the
 * marketplace-bff registration, OAuth2ClientSecretInitializer), and Spring
 * Security does not send {@code code_challenge} for confidential clients by
 * default, so the login request resolver is the official
 * {@link OAuth2AuthorizationRequestCustomizers#withPkce()} customizer on the
 * standard {@link DefaultOAuth2AuthorizationRequestResolver} (javadoc: "adds
 * the {@code code_challenge} and, usually, {@code code_challenge_method}
 * parameters to the OAuth 2.0 Authorization Request"). Without it the edge's
 * first real login is rejected by the issuer's PKCE enforcement — the gap
 * measured while activating the edge service on v4 (2026-10-09).</li>
 * <li>CSRF: {@code csrf.spa()} (Spring Security reference, CSRF for SPAs —
 * the official recipe in place of the removed custom {@code EdgeCsrfConfig}
 * Customizer).</li>
 * <li>Logout: the documented RP-initiated logout wiring, verbatim from the
 * reference (OIDC Logout, servlet/oauth2/login/logout.html): "Also, you
 * should configure OidcClientInitiatedLogoutSuccessHandler, which implements
 * RP-Initiated Logout" — with the same handler factory and the same
 * {@code {baseUrl}} post-logout landing as the reference's sample (the
 * Spring Security 7.1.1 compliance wave, C-7). The edge client is OIDC
 * (scope: openid), so the recommendation applies; the client registration
 * itself is automatic management ({@code spring.security.oauth2.client.*}
 * properties — Boot's auto-configured repository is simply injected here,
 * no hand-built registration). The authorization server's registered
 * post-logout URI channel ({@code OAUTH_POST_LOGOUT_REDIRECT_URI}, prod
 * fail-fast in the initializer) is what the sent value must match in
 * production.</li>
 * <li>Backend transport: {@link EdgeBackendProperties} guards the TokenRelay
 * connection at binding time — https, loopback, or the explicit
 * {@code allow-insecure-transport} opt-in (the official fail-fast mechanism
 * that replaced the removed manual runner; Boot reference, Third-party
 * Configuration).</li>
 * <li>Authorized clients: the Token Relay default is an in-memory store, and the
 * Spring Cloud Gateway reference says to provide an own
 * {@link OAuth2AuthorizedClientService} for anything more robust; Spring
 * Security documents {@link JdbcOAuth2AuthorizedClientService} for that.
 * Boot's auto-configured repository and the default
 * {@code OAuth2AuthorizedClientManager} pick this bean up automatically.</li>
 * <li>Prod fail-fast: a blank or missing {@code EDGE_CLIENT_SECRET} is a
 * startup failure, never a silent fallback (D6).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EdgeBackendProperties.class)
class EdgeSecurityConfig {

    @Bean
    SecurityFilterChain edgeSecurityFilterChain(HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver) throws Exception {
        http.oauth2Login(login -> login.authorizationEndpoint(endpoint -> endpoint
                .authorizationRequestResolver(authorizationRequestResolver)));
        http.oauth2Client(withDefaults());
        // C-7 (the Spring Security 7.1.1 compliance wave) — the documented
        // RP-initiated logout wiring through the reference's own handler
        // factory below.
        http.logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        // Orchestrator liveness/readiness probes carry no credentials: the
        // health surfaces stay public (same idiom as the main app
        // SecurityConfig — GET /actuator/health + /actuator/health/**),
        // everything else authenticated. Adopted from CodeRabbit r1 (Major,
        // stability).
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated());
        http.csrf((csrf) -> csrf.spa());
        return http.build();
    }

    /**
     * The standard login-request resolver with the official PKCE customizer —
     * the same {@link DefaultOAuth2AuthorizationRequestResolver} the defaults
     * install, customized exactly as its javadoc's See-Also points:
     * {@code setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())}.
     * The base URI is the framework's own default authorization-request base
     * (the chain below does NOT relocate the endpoint, so the resolver must
     * extract the registration id from the default
     * {@code /oauth2/authorization/{registrationId}} pattern — measured: a
     * mismatched base leaves the request unresolved and the entry point loops
     * back onto itself).
     *
     * @param clientRegistrationRepository Boot's property-built registration repository
     * @return the PKCE-carrying authorization request resolver
     */
    @Bean
    OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
                        OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /**
     * The documented handler factory — the reference's own listing:
     * "OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler =
     * new OidcClientInitiatedLogoutSuccessHandler(this.clientRegistrationRepository);
     * // Sets the location that the End-User's User Agent will be redirected to
     * // after the logout has been performed at the Provider
     * oidcLogoutSuccessHandler.setPostLogoutRedirectUri(\"{baseUrl}\");".
     *
     * <p>At logout the handler reads {@code end_session_endpoint} from the
     * client registration's provider configuration metadata (the live
     * discovery document declares it), sends the browser there with the
     * {@code id_token_hint} of the logged-in user and this post-logout
     * redirect URI, and falls back to the default logout success behavior
     * when the authentication is not an OIDC one (a plain local logout).</p>
     *
     * @param clientRegistrationRepository Boot's property-built registration repository
     * @return the RP-initiated logout success handler
     */
    LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        oidcLogoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}");
        return oidcLogoutSuccessHandler;
    }

    /**
     * Active in prod AND staging (the production-parity sandbox — B.2): a
     * blank or missing {@code EDGE_CLIENT_SECRET} is a startup failure,
     * never a silent fallback (D6). The sandbox must exercise the same
     * fail-fast posture so a deployment that passes it behaves the way
     * production behaves. (Union note 2026-10-08: the CWE-319 transport
     * twin retired into main's {@link EdgeBackendProperties} binding-time
     * guard — the official {@code @ConfigurationProperties} fail-fast with
     * the same {@code EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT} escape hatch,
     * pinned by its own EdgeBackendPropertiesTest; this secret guard
     * survives the union because main's class javadoc still promises the
     * D6 prod fail-fast and no other bean enforces it.)
     */
    @Bean
    @Profile({"prod", "staging"})
    ApplicationRunner edgeProdGuard(Environment env) {
        return args -> {
            String secret = env.getProperty("EDGE_CLIENT_SECRET", "");
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "EDGE_CLIENT_SECRET is required in prod (D6) — refusing to start a secret-less confidential client");
            }
        };
    }

    /**
     * The official JDBC authorized-client store (Spring Security reference,
     * OAuth2 Client "Authorized Client Manager" / Token Relay's "provide an
     * own service for anything more robust"): the Token Relay default is
     * in-memory, so authorized clients die with the process; this bean
     * persists them through the V1 migration's schema, and Boot's
     * auto-configured {@code OAuth2AuthorizedClientManager} picks it up.
     */
    @Bean
    OAuth2AuthorizedClientService authorizedClientService(JdbcOperations jdbcOperations,
            ClientRegistrationRepository clientRegistrationRepository) {
        return new JdbcOAuth2AuthorizedClientService(jdbcOperations, clientRegistrationRepository);
    }

}
