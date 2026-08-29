package com.marketplace.catalog;

import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    @Test
    @WithMockUser(roles = "CONSUMER")
    void activate_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.activate(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void pause_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.pause(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void archive_whenNotProviderOrAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> catalogService.archive(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void create_whenProvider_thenInvokes() {
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));
        when(listingRepository.save(any(ProviderListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderListingView result = catalogService.create(providerId, "title", "desc", "cat", 1000L);

        assertThat(result.status()).isEqualTo(ListingStatus.DRAFT.name());
        verify(listingRepository).save(any(ProviderListing.class));
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void update_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderListing listing = ProviderListing.create(providerId, "old", "desc", "cat", 1000L);
        UUID listingId = listing.getId();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(currentUserId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));

        ProviderListing result = catalogService.update(listingId, "new", "desc", "cat", 1000L, authentication);

        assertThat(result.getTitle()).isEqualTo("new");
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void activate_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderListing listing = ProviderListing.create(providerId, "title", "desc", "cat", 1000L);
        UUID listingId = listing.getId();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(currentUserId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));

        ProviderListing result = catalogService.activate(listingId, authentication);

        assertThat(result.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void pause_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderListing listing = ProviderListing.create(providerId, "title", "desc", "cat", 1000L);
        listing.activate();
        UUID listingId = listing.getId();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(currentUserId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));

        ProviderListing result = catalogService.pause(listingId, authentication);

        assertThat(result.getStatus()).isEqualTo(ListingStatus.PAUSED);
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void archiveListing_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderListing listing = ProviderListing.create(providerId, "title", "desc", "cat", 1000L);
        UUID listingId = listing.getId();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(currentUserId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));

        ProviderListingSummary result = catalogService.archiveListing(listingId, authentication);

        assertThat(result).isNotNull();
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void archive_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID currentUserId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderListing listing = ProviderListing.create(providerId, "title", "desc", "cat", 1000L);
        UUID listingId = listing.getId();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(currentUserId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "Provider", "VERIFIED", currentUserId)));

        ProviderListing result = catalogService.archive(listingId, authentication);

        assertThat(result.getStatus()).isEqualTo(ListingStatus.ARCHIVED);
    }
}
