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
import org.springframework.security.web.SecurityFilterChain;

/**
 * Edge security: OAuth2 login as the confidential {@code edge} client
 * (client-hosting-strategy-plan §4 pattern 1) + D6 prod fail-fast on a
 * missing client secret (mirrors the marketplace-app JWK hardening).
 */
@Configuration
public class EdgeSecurityConfig {

    @Bean
    SecurityFilterChain edgeSecurityFilterChain(HttpSecurity http,
            Customizer<CsrfConfigurer<HttpSecurity>> edgeCsrf) throws Exception {
        http.oauth2Login(login -> {
        });
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
