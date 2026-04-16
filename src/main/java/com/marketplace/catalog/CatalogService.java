package com.marketplace.catalog;

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

import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class CatalogService {

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

    @Transactional(readOnly = true)
    public Page<ListingSummary> listActive(Pageable pageable) {
        return toSummaryPage(listingRepository.findByStatus(ListingStatus.ACTIVE, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listByCategory(String category, Pageable pageable) {
        return toSummaryPage(listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listByProvider(UUID providerId, Pageable pageable) {
        return listingRepository.findByProviderId(providerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> findAll(Pageable pageable) {
        return listingRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> searchFullText(String tsQuery, Pageable pageable) {
        return toSummaryPage(listingRepository.searchFullText(tsQuery, pageable));
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

    private Page<ListingSummary> toSummaryPage(Page<ProviderListing> listings) {
        var providerIds = listings.getContent().stream()
                .map(ProviderListing::getProviderId)
                .collect(Collectors.toSet());
        var names = providerNameResolver.resolveNames(providerIds);
        return listings.map(listing -> toSummary(listing, names.getOrDefault(listing.getProviderId(), null)));
    }

    private ListingSummary toSummary(ProviderListing listing, String providerName) {
        return new ListingSummary(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                java.math.BigDecimal.valueOf(listing.getPriceCents(), 2),
                providerName
        );
    }
}
