package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { ProviderService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class ProviderServiceSecurityTest {

    @Autowired
    private ProviderService providerService;

    @MockitoBean
    private ProviderRepository providerRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(roles = "USER")
    void create_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> providerService.create("Test", "bio", UUID.randomUUID()));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void update_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> providerService.update(UUID.randomUUID(), "Test", "bio", null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void verify_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> providerService.verify(UUID.randomUUID()));
    }

    @Test
    @WithMockUser(roles = "USER")
    void suspend_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> providerService.suspend(UUID.randomUUID()));
    }
}
