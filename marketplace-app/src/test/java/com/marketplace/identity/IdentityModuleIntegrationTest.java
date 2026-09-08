package com.marketplace.identity;

import test.config.ModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class IdentityModuleIntegrationTest {

    // L23: UserService now consumes the framework-managed UserDetailsManager
    // (SecurityConfig bean in platform-infra) — outside this module slice, so
    // the standard @MockitoBean pattern (house convention, see
    // ReviewsModuleIntegrationTest) applies.
    @MockitoBean
    UserDetailsManager userDetailsManager;

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
