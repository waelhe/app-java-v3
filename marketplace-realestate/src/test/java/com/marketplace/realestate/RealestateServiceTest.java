package com.marketplace.realestate;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L31 service unit tests: the media-module ownership discipline (404
 * unknown listing, 403 foreign provider), the location gate (404 against
 * the geo tree), the idempotent upsert, and the ACTIVE-listing gate of the
 * public read.
 */
@ExtendWith(MockitoExtension.class)
class RealestateServiceTest {

    @Mock
    private PropertyDetailsRepository repository;
    @Mock
    private ListingPriceProvider listingPriceProvider;
    @Mock
    private CatalogSpi catalogSpi;
    @Mock
    private GeoLookupPort geoLookupPort;
    @Mock
    private ProviderLookupPort providerLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private Authentication authentication;

    private RealestateService service;

    private final UUID listingId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID currentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RealestateService(repository, listingPriceProvider, catalogSpi,
                geoLookupPort, providerLookupPort, currentUserProvider, eventPublisher);
        lenient().when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 35000L, "SAR"));
        lenient().when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(currentUserId);
        lenient().when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new com.marketplace.shared.api.ProviderSummary(
                        providerId, "Provider", "VERIFIED", currentUserId)));
    }

    private PropertyDetailsRequest request() {
        return new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                120, 3, 2, 2, 5, 2015, true, List.of("elevator"), null, null, null, null);
    }

    @Test
    void upsert_unknownListing_is404() {
        when(listingPriceProvider.getListingInfo(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));

        assertThatThrownBy(() -> service.upsert(listingId, request(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_foreignProvider_is403() {
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new com.marketplace.shared.api.ProviderSummary(
                        providerId, "Provider", "VERIFIED", UUID.randomUUID())));

        assertThatThrownBy(() -> service.upsert(listingId, request(), authentication))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_unknownLocation_is404BeforeAnyWrite() {
        UUID unknownLocation = UUID.randomUUID();
        when(geoLookupPort.getLocation(unknownLocation))
                .thenThrow(new ResourceNotFoundException("GeoLocation", unknownLocation));

        assertThatThrownBy(() -> service.upsert(listingId,
                new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.VILLA,
                        null, null, null, null, null, null, null, null, null,
                        unknownLocation, null, null), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_createsWhenAbsent() {
        when(repository.findByListingId(listingId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.upsert(listingId, request(), authentication);

        assertThat(view.listingId()).isEqualTo(listingId);
        assertThat(view.purpose()).isEqualTo(PropertyPurpose.RENT);
        verify(repository).save(any(PropertyDetails.class));
    }

    @Test
    void upsert_replacesWhenPresent_idempotentByListing() {
        PropertyDetails existing = PropertyDetails.create(listingId, providerId, request());
        when(repository.findByListingId(listingId)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.upsert(listingId,
                new PropertyDetailsRequest(PropertyPurpose.SALE, PropertyType.VILLA,
                        300, null, null, null, null, null, null, null, null,
                        null, null, null), authentication);

        assertThat(view.purpose()).isEqualTo(PropertyPurpose.SALE);
        assertThat(view.areaM2()).isEqualTo(300);
        verify(repository).save(existing);
    }

    @Test
    void upsert_invalidField_is400BeforeAnyWrite() {
        assertThatThrownBy(() -> service.upsert(listingId,
                new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                        0, null, null, null, null, null, null, null, null, null, null, null),
                authentication))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void publicRead_inactiveListing_is404FromTheCatalogGate() {
        when(catalogSpi.getActiveById(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));

        assertThatThrownBy(() -> service.getPublicByListingId(listingId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).findByListingId(listingId);
    }

    @Test
    void publicRead_absentDetails_is404() {
        when(catalogSpi.getActiveById(listingId)).thenReturn(null);
        when(repository.findByListingId(listingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicByListingId(listingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicRead_activeListingWithDetails_servesTheView() {
        when(catalogSpi.getActiveById(listingId)).thenReturn(null);
        when(repository.findByListingId(listingId))
                .thenReturn(Optional.of(PropertyDetails.create(listingId, providerId, request())));

        var view = service.getPublicByListingId(listingId);

        assertThat(view.purpose()).isEqualTo(PropertyPurpose.RENT);
    }

    @Test
    void findByListingIds_batchesOneQuery() {
        UUID other = UUID.randomUUID();
        when(repository.findByListingIdIn(java.util.Set.of(listingId, other)))
                .thenReturn(List.of(PropertyDetails.create(listingId, providerId, request())));

        var map = service.findByListingIds(java.util.Set.of(listingId, other));

        assertThat(map).containsKey(listingId).doesNotContainKey(other);
    }

    @Test
    void upsert_publishesSearchCacheInvalidation_afterTheFacetSetChanged() {
        when(repository.findByListingId(listingId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(listingId, request(), authentication);

        ArgumentCaptor<com.marketplace.shared.api.CacheInvalidationRequested> captor =
                ArgumentCaptor.forClass(com.marketplace.shared.api.CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().cacheNames())
                .containsExactlyInAnyOrderElementsOf(RealestateService.REALESTATE_CACHE_NAMES);
    }
}
