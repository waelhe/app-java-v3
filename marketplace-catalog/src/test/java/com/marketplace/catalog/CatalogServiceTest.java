package com.marketplace.catalog;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private final ProviderListingRepository repository = mock(ProviderListingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProviderNameResolver providerNameResolver = mock(ProviderNameResolver.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private CatalogService service;
    private final UUID providerId = UUID.randomUUID();
    private final Authentication auth = new UsernamePasswordAuthenticationToken(
            "provider", "", List.of(new SimpleGrantedAuthority("ROLE_PROVIDER")));

    @BeforeEach
    void setUp() {
        service = new CatalogService(repository, currentUserProvider, providerNameResolver, eventPublisher);
    }

    @Test
    void getByIdReturnsListing() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        assertEquals(listing, service.getById(id));
    }

    @Test
    void getByIdThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
    }

    @Test
    void createSavesAndPublishesEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(providerId, "Plumber", "Expert", "services", 5000L);

        assertNotNull(result);
        assertEquals(providerId, result.getProviderId());
        verify(repository).save(any());
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void listActiveReturnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(UUID.randomUUID());
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Test");
        when(listing.getCategory()).thenReturn("services");
        when(listing.getPriceCents()).thenReturn(5000L);
        when(repository.findByStatus(ListingStatus.ACTIVE, pageable))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(providerNameResolver.resolveNames(any())).thenReturn(Map.of(providerId, "Provider Name"));

        var result = service.listActive(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals("Test", result.getContent().getFirst().title());
    }

    @Test
    void listByCategoryReturnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(UUID.randomUUID());
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Plumber");
        when(listing.getCategory()).thenReturn("plumbing");
        when(listing.getPriceCents()).thenReturn(3000L);
        when(repository.findByCategoryAndStatus("plumbing", ListingStatus.ACTIVE, pageable))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(providerNameResolver.resolveNames(any())).thenReturn(Map.of(providerId, "Provider"));

        var result = service.listByCategory("plumbing", pageable);

        assertEquals(1, result.getContent().size());
        assertEquals("Plumber", result.getContent().getFirst().title());
    }

    @Test
    void listByProviderReturnsListings() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(repository.findByProviderId(providerId, pageable))
                .thenReturn(new PageImpl<>(List.of(listing)));

        var result = service.listByProvider(providerId, pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void findAllReturnsAllListings() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(listing)));

        var result = service.findAll(pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void searchFullTextReturnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(UUID.randomUUID());
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Plumber");
        when(listing.getCategory()).thenReturn("services");
        when(listing.getPriceCents()).thenReturn(5000L);
        when(repository.searchFullText("plumber", pageable))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(providerNameResolver.resolveNames(any())).thenReturn(Map.of(providerId, "Name"));

        var result = service.searchFullText("plumber", pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void searchByCriteriaWithPriceRange() {
        var pageable = PageRequest.of(0, 10);
        var criteria = new SearchCriteria(null, "services", BigDecimal.valueOf(10), BigDecimal.valueOf(100));
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(UUID.randomUUID());
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Test");
        when(listing.getCategory()).thenReturn("services");
        when(listing.getPriceCents()).thenReturn(5000L);
        when(repository.searchByCriteria(eq("services"), eq(1000L), eq(10000L), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(providerNameResolver.resolveNames(any())).thenReturn(Map.of(providerId, "Name"));

        var result = service.searchByCriteria(criteria, pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void searchByCriteriaWithNullPrices() {
        var pageable = PageRequest.of(0, 10);
        var criteria = new SearchCriteria(null, "services", null, null);
        when(repository.searchByCriteria(eq("services"), eq(null), eq(null), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = service.searchByCriteria(criteria, pageable);

        assertTrue(result.getContent().isEmpty());
    }

    @Test
    void updateModifiesListing() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(providerId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        service.update(id, "New", "New desc", "new-cat", 5000L, auth);

        verify(listing).update("New", "New desc", "new-cat", 5000L);
    }

    @Test
    void activateChangesStatus() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(providerId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        service.activate(id, auth);

        verify(listing).activate();
    }

    @Test
    void pauseChangesStatus() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(providerId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        service.pause(id, auth);

        verify(listing).pause();
    }

    @Test
    void archiveChangesStatus() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(providerId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        service.archive(id, auth);

        verify(listing).archive();
    }

    @Test
    void archiveListingReturnsSummary() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(id);
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Test");
        when(listing.getCategory()).thenReturn("services");
        when(listing.getPriceCents()).thenReturn(5000L);
        when(listing.getStatus()).thenReturn(ListingStatus.ARCHIVED);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        var result = service.archiveListing(id, auth);

        assertEquals(id, result.id());
    }

    @Test
    void verifyOwnershipThrowsWhenNotOwner() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        var otherUserId = UUID.randomUUID();
        when(listing.getProviderId()).thenReturn(otherUserId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(auth)).thenReturn(false);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        assertThrows(AccessDeniedException.class, () -> service.update(id, "T", "D", "C", 1L, auth));
    }

    @Test
    void verifyOwnershipAllowsAdmin() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(UUID.randomUUID());
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(auth)).thenReturn(true);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        service.update(id, "T", "D", "C", 1L, auth);

        verify(listing).update("T", "D", "C", 1L);
    }

    @Test
    void getListingInfoReturnsProviderIdAndPrice() {
        var id = UUID.randomUUID();
        var listing = mock(ProviderListing.class);
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getPriceCents()).thenReturn(5000L);
        when(repository.findById(id)).thenReturn(Optional.of(listing));

        var info = service.getListingInfo(id);

        assertEquals(providerId, info.providerId());
        assertEquals(5000L, info.priceCents());
    }

    @Test
    void findAllSummariesReturnsProviderListingSummaries() {
        var pageable = PageRequest.of(0, 10);
        var listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(UUID.randomUUID());
        when(listing.getProviderId()).thenReturn(providerId);
        when(listing.getTitle()).thenReturn("Test");
        when(listing.getCategory()).thenReturn("services");
        when(listing.getPriceCents()).thenReturn(5000L);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(listing)));

        var result = service.findAllSummaries(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals("Test", result.getContent().getFirst().title());
    }
}
