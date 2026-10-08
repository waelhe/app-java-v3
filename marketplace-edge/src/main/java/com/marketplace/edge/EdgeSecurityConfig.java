package com.marketplace.edge;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;

/**
 * Edge security: OAuth2 login as the confidential {@code edge} client
 * (client-hosting-strategy-plan §4 pattern 1) + D6 prod fail-fast on a
 * missing client secret (mirrors the marketplace-app JWK hardening) +
 * RP-initiated logout (the Spring Security 7.1.1 compliance wave, C-7).
 */
@Configuration
public class EdgeSecurityConfig {

    @Bean
    SecurityFilterChain edgeSecurityFilterChain(HttpSecurity http,
            Customizer<CsrfConfigurer<HttpSecurity>> edgeCsrf,
            ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http.oauth2Login(login -> {
        });
        // C-7 (the Spring Security 7.1.1 compliance wave) — the documented
        // RP-initiated logout wiring, verbatim from the reference (OIDC
        // Logout, servlet/oauth2/login/logout.html): "Also, you should
        // configure OidcClientInitiatedLogoutSuccessHandler, which implements
        // RP-Initiated Logout, as follows: ... http .logout((logout) -> logout
        // .logoutSuccessHandler(oidcLogoutSuccessHandler()));" — with the
        // same handler factory and the same {baseUrl} post-logout landing as
        // the reference's sample. The edge client is OIDC (scope: openid),
        // so the recommendation applies; the client registration itself is
        // automatic management (spring.security.oauth2.client.* properties —
        // Boot's auto-configured repository is simply injected here, no
        // hand-built registration). The authorization server's registered
        // post-logout URI channel (OAUTH_POST_LOGOUT_REDIRECT_URI, prod
        // fail-fast in the initializer) is what the sent value must match in
        // production.
        http.logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        // Orchestrator liveness/readiness probes carry no credentials: health
        // stays public (same idiom as the main app SecurityConfig —
        // GET /actuator/health/** + /actuator/info permitAll), everything
        // else authenticated. Adopted from CodeRabbit r1 (Major, stability).
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated());
        http.csrf(edgeCsrf);
        return http.build();
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
     * Active on prod only: a blank or missing {@code EDGE_CLIENT_SECRET} is a
     * startup failure, never a silent fallback (D6).
     */
    @Bean
    @Profile("prod")
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
     * Active on prod only: the relayed bearer must not travel over cleartext
     * in a deployed environment (CWE-319 — adopted from CodeRabbit r1, Minor).
     * HTTP stays allowed for local development (any non-prod profile skips
     * this guard entirely) and for platform private-network hops behind an
     * explicit {@code EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT=true} escape hatch
     * (documented, auditable, never silent).
     */
    @Bean
    @Profile("prod")
    ApplicationRunner edgeTransportGuard(Environment env) {
        return args -> {
            String backend = env.getProperty("EDGE_BACKEND_URL", "http://localhost:8080");
            boolean allowed = env.getProperty("EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT", Boolean.class, false);
            if (!allowed && backend != null && backend.toLowerCase(java.util.Locale.ROOT).startsWith("http://")) {
                throw new IllegalStateException(
                        "EDGE_BACKEND_URL must use https in prod (CWE-319) — refusing cleartext bearer relay; "
                                + "set EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT=true only for private-network hops");
            }
        };
    }
}
