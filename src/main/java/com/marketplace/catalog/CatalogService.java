package com.marketplace.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class CatalogService {

    private final ProviderListingRepository listingRepository;

    public CatalogService(ProviderListingRepository listingRepository) {
        this.listingRepository = listingRepository;
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
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + id));
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing create(UUID providerId, String title, String description,
                                  String category, Long priceCents) {
        ProviderListing listing = ProviderListing.create(providerId, title, description, category, priceCents);
        return listingRepository.save(listing);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing update(UUID id, String title, String description,
                                  String category, Long priceCents) {
        ProviderListing listing = getById(id);
        listing.update(title, description, category, priceCents);
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing activate(UUID id) {
        ProviderListing listing = getById(id);
        listing.activate();
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing pause(UUID id) {
        ProviderListing listing = getById(id);
        listing.pause();
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing archive(UUID id) {
        ProviderListing listing = getById(id);
        listing.archive();
        return listing;
    }
}