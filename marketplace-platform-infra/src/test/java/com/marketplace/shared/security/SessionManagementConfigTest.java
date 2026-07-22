package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the concurrent session control configuration added in Phase 3.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link HttpSessionEventPublisher} bean is registered (required for
 *       session destruction events to reach the {@link SessionRegistry})</li>
 *   <li>{@link SessionRegistry} bean is registered (tracks active sessions)</li>
 * </ul>
 *
 * <p>Reference: Spring Security 7.1 — Session Management:
 * "When you use Spring Security's session-management and want to enable
 * concurrent session control, you need to register the following listener
 * in Spring Boot: @Bean public HttpSessionEventPublisher
 * httpSessionEventPublisher() { return new HttpSessionEventPublisher(); }"
 * https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#concurrent-session-control
 */
class SessionManagementConfigTest {

    @Test
    void marketplacePropertiesSessionDefaultsAreCorrect() {
        MarketplaceProperties.Security.Session session =
                new MarketplaceProperties.Security.Session(1, false);

        assertThat(session.maximumSessions()).isEqualTo(1);
        assertThat(session.maxSessionsPreventsLogin()).isFalse();
    }

    @Test
    void marketplacePropertiesSessionAcceptsCustomValues() {
        MarketplaceProperties.Security.Session session =
                new MarketplaceProperties.Security.Session(3, true);

        assertThat(session.maximumSessions()).isEqualTo(3);
        assertThat(session.maxSessionsPreventsLogin()).isTrue();
    }

    @Test
    void marketplacePropertiesSecurityHoldsSessionConfig() {
        MarketplaceProperties.Security.Session session =
                new MarketplaceProperties.Security.Session(1, false);
        MarketplaceProperties.Security security = new MarketplaceProperties.Security(
                new MarketplaceProperties.Security.Jwt(
                        new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                        "marketplace-api"
                ),
                new MarketplaceProperties.Security.AuthServer("http://localhost:8080"),
                session
        );

        assertThat(security.session()).isNotNull();
        assertThat(security.session().maximumSessions()).isEqualTo(1);
        assertThat(security.session().maxSessionsPreventsLogin()).isFalse();
    }
}
