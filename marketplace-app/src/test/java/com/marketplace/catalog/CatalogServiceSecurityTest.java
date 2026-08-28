package com.marketplace.catalog;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { CatalogService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class CatalogServiceSecurityTest {

    @Autowired
    private CatalogService catalogService;

    @MockitoBean
    private ProviderListingRepository listingRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderNameResolver providerNameResolver;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @Test
    @WithMockUser(roles = "USER")
    void create_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.create(UUID.randomUUID(), "title", "desc", "cat", 1000L));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void update_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.update(UUID.randomUUID(), "title", "desc", "cat", 1000L, null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void archiveListing_whenNotProviderOrAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.archiveListing(UUID.randomUUID(), null));
    }
}
