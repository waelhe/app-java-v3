package com.marketplace.catalog;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class CatalogService implements CatalogSearchPort {

    private final ProviderListingRepository listingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderNameResolver providerNameResolver;

    public CatalogService(ProviderListingRepository listingRepository,
                          CurrentUserProvider currentUserProvider,
                          ProviderNameResolver providerNameResolver) {
        this.listingRepository = listingRepository;
        this.currentUserProvider = currentUserProvider;
        this.providerNameResolver = providerNameResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> listActive(Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findByStatus(ListingStatus.ACTIVE, pageable);
        return toSummaryPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> listByCategory(String category, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable);
        return toSummaryPage(page);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listByProvider(UUID providerId, Pageable pageable) {
        return listingRepository.findByProviderId(providerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> findAll(Pageable pageable) {
        return listingRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchFullText(String tsQuery, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.searchFullText(tsQuery, pageable);
        return toSummaryPage(page);
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listByCategorySummary(String category, Pageable pageable) {
        return listByCategory(category, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listActiveSummary(Pageable pageable) {
        return listActive(pageable);
    }

    @Transactional(readOnly = true)
    public ProviderListing getById(UUID id) {
        return listingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", id));
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing create(UUID providerId, String title, String description,
                                  String category, Long priceCents) {
        ProviderListing listing = ProviderListing.create(providerId, title, description, category, priceCents);
        return listingRepository.save(listing);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing update(UUID id, String title, String description,
                                  String category, Long priceCents, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.update(title, description, category, priceCents);
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing activate(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.activate();
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing pause(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.pause();
        return listing;
    }

    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ProviderListing archive(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.archive();
        return listing;
    }

    private void verifyOwnership(ProviderListing listing, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!listing.getProviderId().equals(currentUserId) && !currentUserProvider.isAdmin(authentication)) {
            throw new IllegalArgumentException("You do not own this listing");
        }
    }

    /**
     * Batch-resolve provider names for an entire page, then map to ListingSummary.
     * Avoids N+1 queries by calling resolveNames once per page.
     */
    private Page<ListingSummary> toSummaryPage(Page<ProviderListing> page) {
        Set<UUID> providerIds = page.getContent().stream()
                .map(ProviderListing::getProviderId)
                .collect(Collectors.toSet());
        Map<UUID, String> providerNames = providerNameResolver.resolveNames(providerIds);
        return page.map(listing -> new ListingSummary(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                java.math.BigDecimal.valueOf(listing.getPriceCents(), 2),
                providerNames.getOrDefault(listing.getProviderId(), "Unknown Provider")
        ));
    }
}
