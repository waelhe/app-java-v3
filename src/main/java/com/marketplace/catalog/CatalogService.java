package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class CatalogService {

    private final ProviderListingRepository listingRepository;
    private final SecurityUtils securityUtils;

    public CatalogService(ProviderListingRepository listingRepository, SecurityUtils securityUtils) {
        this.listingRepository = listingRepository;
        this.securityUtils = securityUtils;
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listActive(Pageable pageable) {
        return listingRepository.findByStatus(ListingStatus.ACTIVE, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listByCategory(String category, Pageable pageable) {
        return listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listByProvider(UUID providerId, Pageable pageable) {
        return listingRepository.findByProviderId(providerId, pageable);
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

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing archive(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.archive();
        return listing;
    }

    private void verifyOwnership(ProviderListing listing, Authentication authentication) {
        UUID currentUserId = securityUtils.getCurrentUserId(authentication);
        if (!listing.getProviderId().equals(currentUserId) && !securityUtils.isAdmin(authentication)) {
            throw new IllegalArgumentException("You do not own this listing");
        }
    }
}