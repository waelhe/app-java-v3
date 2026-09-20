package com.marketplace.edge;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Edge security: OAuth2 login as the confidential {@code edge} client
 * (client-hosting-strategy-plan §4 pattern 1) + D6 prod fail-fast on a
 * missing client secret (mirrors the marketplace-app JWK hardening).
 */
@Configuration
public class EdgeSecurityConfig {

    @Bean
    SecurityFilterChain edgeSecurityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2Login(login -> {
        });
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
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
}
