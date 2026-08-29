package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void create_whenConsumer_thenInvokes() {
        UUID userId = UUID.randomUUID();
        when(providerRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderProfile result = providerService.create("Test", "bio", userId);

        assertThat(result.getStatus()).isEqualTo(ProviderStatus.PENDING);
        verify(providerRepository).save(any(ProviderProfile.class));
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void update_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = ProviderProfile.create("Test", "bio", userId);
        UUID providerId = provider.getId();
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);

        ProviderProfile result = providerService.update(providerId, "New", "bio2", authentication);

        assertThat(result.getDisplayName()).isEqualTo("New");
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void verify_whenAdmin_thenInvokes() {
        ProviderProfile provider = ProviderProfile.create("Test", "bio", UUID.randomUUID());
        UUID providerId = provider.getId();
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));

        ProviderProfile result = providerService.verify(providerId);

        assertThat(result.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void suspend_whenAdmin_thenInvokes() {
        ProviderProfile provider = ProviderProfile.create("Test", "bio", UUID.randomUUID());
        UUID providerId = provider.getId();
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));

        ProviderProfile result = providerService.suspend(providerId);

        assertThat(result.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }
}
