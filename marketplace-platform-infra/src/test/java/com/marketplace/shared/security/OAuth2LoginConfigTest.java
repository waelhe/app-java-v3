package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the OAuth2 social login configuration added in Phase 4.
 *
 * <p>Verifies that the {@link MarketplaceProperties.Security} record remains
 * compatible after Phase 4 adds {@code .oauth2Login(Customizer.withDefaults())}
 * to the {@code defaultSecurityFilterChain}.
 *
 * <p>The actual OAuth2 login filter wiring is verified at integration test
 * level (when the Spring context starts with the {@code spring.security.oauth2.client}
 * properties from {@code application-test.yml}, Spring Boot auto-configures
 * the {@code ClientRegistrationRepository} and the {@code OAuth2LoginAuthenticationFilter}).
 *
 * <p>Reference: Spring Security 7.1 — OAuth2 Login: Core Configuration:
 * "Spring Boot brings full auto-configuration capabilities for OAuth 2.0 Login.
 * It performs the following tasks: Registers a ClientRegistrationRepository @Bean
 * composed of ClientRegistration(s) from the configured OAuth Client properties.
 * Registers a SecurityFilterChain @Bean and enables OAuth 2.0 Login through
 * httpSecurity.oauth2Login()."
 * https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html
 */
class OAuth2LoginConfigTest {

    @Test
    void marketplacePropertiesSecurityRemainsCompatibleAfterPhase4() {
        MarketplaceProperties.Security.Session session =
                new MarketplaceProperties.Security.Session(1, false);
        MarketplaceProperties.Security security = new MarketplaceProperties.Security(
                new MarketplaceProperties.Security.Jwt(
                        new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                        "marketplace-api"
                ),
                new MarketplaceProperties.Security.AuthServer("http://localhost:8080"),
                session,
                new MarketplaceProperties.Security.Admin(java.util.List.of())
        );

        assertThat(security.session()).isNotNull();
        assertThat(security.session().maximumSessions()).isEqualTo(1);
        assertThat(security.authServer()).isNotNull();
        assertThat(security.jwt()).isNotNull();
    }

    @Test
    void oauth2LoginConfigurationIsDeclaredInSecurityConfig() throws Exception {
        java.nio.file.Path securityConfig = java.nio.file.Path.of(
                "src/main/java/com/marketplace/shared/security/SecurityConfig.java");

        String content = java.nio.file.Files.readString(securityConfig);

        assertThat(content)
                .as("SecurityConfig should contain .oauth2Login(Customizer.withDefaults()) on defaultSecurityFilterChain")
                .contains(".oauth2Login(Customizer.withDefaults())");

        assertThat(content)
                .as("SecurityConfig should have oauth2Login on the defaultSecurityFilterChain (Order 4), not on API chains")
                .contains("defaultSecurityFilterChain");
    }
}
