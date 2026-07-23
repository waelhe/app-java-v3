package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative security tests verifying the authorization rules declared in
 * {@link SecurityConfig} (Phase 5).
 *
 * <p>Verifies that the {@code protectedApiSecurityFilterChain} (Order 3)
 * declares the correct authorization rules:
 * <ul>
 *   <li>{@code /api/v1/payments/webhooks/**} → permitAll (webhook callbacks)</li>
 *   <li>{@code /api/v1/admin/**} → hasRole('ADMIN')</li>
 *   <li>all other {@code /api/**} → authenticated</li>
 * </ul>
 *
 * <p>This is a static analysis test (reads the SecurityConfig source) because
 * building the full {@code HttpSecurity} filter chain in a unit test requires
 * the complete Spring context. The runtime behavior (401/403 responses) is
 * verified by {@code SecurityProblemDetailIntegrationTest} in marketplace-app,
 * which uses {@code @SpringBootTest} with the full security filter chain.
 *
 * <p>Per Governance Rule 7: "Security features require explicit authorization
 * rules and tests."
 *
 * <p>Reference: Spring Security 7.1 — Authorization Architecture:
 * "If the user does not have the required authority, Spring Security returns
 * a 403 Forbidden HTTP status code."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
 */
class SecurityNegativeTests {

    private static final Path SECURITY_CONFIG =
            Path.of("src/main/java/com/marketplace/shared/security/SecurityConfig.java");

    @Test
    void protectedApiChainRequiresAuthenticationForAllApiEndpoints() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("protectedApiSecurityFilterChain must enforce .anyRequest().authenticated() on /api/**")
                .contains(".anyRequest().authenticated()");
    }

    @Test
    void adminEndpointsUseAuthorizationManager() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("Admin endpoints /api/v1/admin/** must use AdminIpAuthorizationManager for IP-based access control")
                .contains("/api/v1/admin/**")
                .contains(".access(adminIpAuthorizationManager)");
    }

    @Test
    void webhookEndpointsArePermitAll() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("Payment webhook endpoints must be permitAll (called by payment provider)")
                .contains("/api/v1/payments/webhooks/**")
                .contains("permitAll");
    }

    @Test
    void apiChainsAreStateless() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("API chains (Orders 2, 3) must use SessionCreationPolicy.STATELESS")
                .contains("SessionCreationPolicy.STATELESS");
    }

    @Test
    void defaultChainHasFormLoginAndOAuth2LoginAndSessionManagement() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("defaultSecurityFilterChain must have formLogin (Phase 1)")
                .contains(".formLogin(Customizer.withDefaults())");

        assertThat(content)
                .as("defaultSecurityFilterChain must have oauth2Login (Phase 4)")
                .contains(".oauth2Login(Customizer.withDefaults())");

        assertThat(content)
                .as("defaultSecurityFilterChain must have sessionManagement with maximumSessions (Phase 3)")
                .contains(".maximumSessions(");

        assertThat(content)
                .as("defaultSecurityFilterChain must have HttpSessionEventPublisher (Phase 3)")
                .contains("HttpSessionEventPublisher");
    }

    @Test
    void jwtTokenCustomizerAddsJtiClaim() throws Exception {
        String content = Files.readString(SECURITY_CONFIG);

        assertThat(content)
                .as("OAuth2TokenCustomizer must add jti claim (Phase 1 — RFC 9068 §2.2)")
                .contains("OAuth2TokenCustomizer<JwtEncodingContext>")
                .contains("jti");
    }
}
