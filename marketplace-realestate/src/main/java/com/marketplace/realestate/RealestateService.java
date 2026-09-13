package com.marketplace.realestate;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The realestate module's surface (realestate systems plan L31). Implements
 * {@link PropertyDetailsPort} so catalog embeds the property block through
 * the shared abstraction (the {@code ProviderNameResolver} pattern).
 *
 * <p>Write path — the media module's exact ownership discipline
 * ("MediaService follows the catalog port for existence and ownership"):
 * the listing is resolved through {@link ListingPriceProvider} (any status
 * — property details are writable on DRAFT listings too, they become
 * PUBLIC only with the ACTIVE listing), the caller's ownership through
 * {@code ProviderLookupPort.findByUserId} against the listing's
 * {@code providerId} (the users.id space, agreement A1).
 *
 * <p>Read path — the public GET gates on the listing being ACTIVE via
 * {@link CatalogSpi#getActiveById} (details are visible exactly with live
 * listings; an archived or soft-deleted listing hides them — the plan's
 * "deletion follows the listing").
 */
@Service
@Transactional
public class RealestateService implements PropertyDetailsPort {

    /**
     * A property write changes the faceted-search matching set — the cached
     * {@code search-results-v3} pages must evict through the existing
     * AFTER_COMMIT relay (the same freshness contract every catalog write
     * already carries; L32's facet results are as stale-prone as price).
     */
    public static final java.util.Set<String> REALESTATE_CACHE_NAMES =
            java.util.Set.of("search-results-v3");

    private final PropertyDetailsRepository repository;
    private final ListingPriceProvider listingPriceProvider;
    private final CatalogSpi catalogSpi;
    private final GeoLookupPort geoLookupPort;
    private final ProviderLookupPort providerLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public RealestateService(PropertyDetailsRepository repository,
                             ListingPriceProvider listingPriceProvider,
                             CatalogSpi catalogSpi,
                             GeoLookupPort geoLookupPort,
                             ProviderLookupPort providerLookupPort,
                             CurrentUserProvider currentUserProvider,
                             ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.listingPriceProvider = listingPriceProvider;
        this.catalogSpi = catalogSpi;
        this.geoLookupPort = geoLookupPort;
        this.providerLookupPort = providerLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The upsert (the plan's PUT): resolves the listing (404), verifies the
     * caller owns it (403), validates the location against the geo tree
     * (404 — an unknown node is never silently accepted), then replaces the
     * whole field set atomically.
     */
    @Observed(name = "realestate.property.upsert")
    @PreAuthorize("hasRole('PROVIDER')")
    public PropertyView upsert(UUID listingId, PropertyDetailsRequest request,
                               Authentication authentication) {
        ListingPriceProvider.ListingInfo listing = listingPriceProvider.getListingInfo(listingId);
        verifyListingOwnership(listing.providerId(), authentication);
        if (request.locationId() != null) {
            geoLookupPort.getLocation(request.locationId());
        }
        PropertyDetails details = repository.findByListingId(listingId)
                .orElseGet(() -> PropertyDetails.create(listingId, listing.providerId(), request));
        details.apply(request);
        PropertyDetails saved = repository.save(details);
        eventPublisher.publishEvent(new CacheInvalidationRequested(REALESTATE_CACHE_NAMES));
        return toView(saved);
    }

    /**
     * The public read: visible exactly with an ACTIVE listing (the catalog
     * gate answers 404 otherwise — archived, paused or soft-deleted), and
     * 404 when the listing has no property block.
     */
    @Transactional(readOnly = true)
    public PropertyView getPublicByListingId(UUID listingId) {
        catalogSpi.getActiveById(listingId);
        return repository.findByListingId(listingId)
                .map(RealestateService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("PropertyDetails", listingId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PropertyView> findByListingId(UUID listingId) {
        return repository.findByListingId(listingId).map(RealestateService::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PropertyView> findByListingIds(Set<UUID> listingIds) {
        return listingIds.isEmpty()
                ? Map.of()
                : repository.findByListingIdIn(listingIds).stream()
                        .collect(Collectors.toMap(
                                PropertyDetails::getListingId, RealestateService::toView));
    }

    /** The media module's ownership verification, verbatim. */
    private void verifyListingOwnership(UUID listingProviderId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        providerLookupPort.findByUserId(listingProviderId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElseThrow(() -> new AccessDeniedException("You do not own this listing"));
    }

    private static PropertyView toView(PropertyDetails details) {
        return new PropertyView(
                details.getListingId(),
                details.getPurpose(),
                details.getPropertyType(),
                details.getAreaM2(),
                details.getRooms(),
                details.getBathrooms(),
                details.getFloorNumber(),
                details.getTotalFloors(),
                details.getBuildingYear(),
                details.getFurnished(),
                details.getAmenities(),
                details.getAvailableFrom(),
                details.getLocationId(),
                details.getLatitude(),
                details.getLongitude());
    }
}
