package com.marketplace.edge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;

/**
 * BFF CSRF posture for a JavaScript SPA served through the edge (plan Task 5).
 *
 * <p>Official basis — Spring Security 7.1.1 reference, "Cross Site Request
 * Forgery (CSRF)" (servlet/exploits/csrf.html), section "Single-Page
 * Applications": "In order to easily integrate a single-page application
 * with Spring Security, the following configuration can be used:
 * {@code .csrf((csrf) -> csrf.spa())}". The {@code spa()} recipe persists the
 * token in an {@code XSRF-TOKEN} cookie readable by JavaScript (the Angular
 * HttpClient XSRF convention) while keeping BREACH-safe resolving and
 * refreshing the cookie after login/logout. It neither disables CSRF anywhere
 * (forbidden by the plan) nor opts out of BREACH protection (which a bare
 * {@code CsrfTokenRequestAttributeHandler} would do — reference section
 * "Using the CsrfTokenRequestAttributeHandler").
 *
 * <p>Kept as a {@code Customizer} bean (not a second filter chain) so the
 * single {@code edgeSecurityFilterChain} stays the only chain: split chains
 * would split the auth rules across order positions.
 */
@Configuration
class EdgeCsrfConfig {

    @Bean
    Customizer<CsrfConfigurer<HttpSecurity>> edgeCsrf() {
        return CsrfConfigurer::spa;
    }
}
