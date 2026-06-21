package com.marketplace.identity;

import test.config.ModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses {@link BootstrapMode#ALL_DEPENDENCIES} to load the shared-security module's
 * {@code SecurityConfig}, which provides the {@code MarketplaceProperties},
 * {@code JwtEncoder}, and {@code PasswordEncoder} beans that
 * {@code TwoStepLoginService} (a {@code @Service} in the identity module) requires.
 *
 * <p>In default STANDALONE mode, only the identity module + its declared named-interface
 * dependencies are loaded. {@code MarketplaceProperties} lives in the {@code shared-config}
 * named interface of the {@code shared} module, but it's a {@code @ConfigurationProperties}
 * record (not a {@code @Component}), so it's only registered as a bean via
 * {@code @EnableConfigurationProperties} on {@code MarketplaceApplication}, which
 * STANDALONE mode does not fully process.
 *
 * <p>Reference: https://docs.spring.io/spring-modulith/reference/testing.html
 * "ALL_DEPENDENCIES - All dependencies of the module under test will be bootstrapped."
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class IdentityModuleIntegrationTest {

    @Autowired
    private UserService userService;

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
