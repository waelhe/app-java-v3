package com.marketplace.identity;

import test.config.ModuleTestConfig;
import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the Identity module in STANDALONE mode.
 *
 * <p><b>MarketplaceProperties bean in slice context:</b> The inner
 * {@code IdentityTestConfig} provides a {@code @Primary @Bean} with a <em>real</em>
 * {@link MarketplaceProperties} instance. This is needed because
 * {@code @ApplicationModuleTest} (STANDALONE mode) limits component scanning to
 * the module's base package ({@code com.marketplace.identity}), and
 * {@code TwoStepLoginService}'s constructor injection of {@code MarketplaceProperties}
 * may fail before {@link ModuleTestConfig}'s {@code @Bean} is resolved in the slice
 * context.
 *
 * <p><b>No bean name conflict:</b> The inner config uses a distinct method name
 * ({@code identityMarketplaceProperties}) — both beans coexist, and {@code @Primary}
 * ensures this one wins for injection. This mirrors the pattern used by
 * {@code MessagingModuleIntegrationTest.MessagingTestConfig}.
 *
 * <p><b>Real bean, not mock:</b> Returns a real bean with all properties populated
 * (Cors, Security.Jwt, Security.AuthServer) to avoid NPEs in
 * {@code TwoStepLoginService.issueJwt()}.
 *
 * <p>Reference: https://docs.spring.io/spring-modulith/reference/testing.html
 * "@ApplicationModuleTest bootstraps the module under test."
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ModuleTestConfig.class, IdentityModuleIntegrationTest.IdentityTestConfig.class})
@WithMockUser
class IdentityModuleIntegrationTest {

    @Autowired
    private UserService userService;

    @TestConfiguration
    static class IdentityTestConfig {

        /**
         * Provides a real {@link MarketplaceProperties} bean for the slice context.
         * Uses a distinct method name ({@code identityMarketplaceProperties}) to
         * avoid bean name collision with {@link ModuleTestConfig}'s
         * {@code marketplaceProperties()} method.
         */
        @Bean
        @Primary
        MarketplaceProperties identityMarketplaceProperties() {
            return new MarketplaceProperties(
                    new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                    new MarketplaceProperties.Security(
                            new MarketplaceProperties.Security.Jwt(
                                    new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                                    "marketplace-api"
                            ),
                            new MarketplaceProperties.Security.AuthServer("http://localhost:8080")
                    )
            );
        }
    }

    @Test
    void contextLoads() {
    }

    @Test
    void findAll_returnsEmptyPage() {
        var page = userService.findAll(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void getById_throwsForUnknown() {
        assertThrows(ResourceNotFoundException.class, () -> userService.getById(UUID.randomUUID()));
    }
}
