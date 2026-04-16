package com.marketplace.search;

import com.marketplace.catalog.ListingStatus;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private final ProviderListingRepository listingRepository;

    public SearchService(ProviderListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    public Page<ProviderListing> search(String query, String category, Pageable pageable) {
        if (category != null && !category.isBlank()) {
            return listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable);
        }
        return listingRepository.findByStatus(ListingStatus.ACTIVE, pageable);
    }

    public Page<ProviderListing> searchByCategory(String category, Pageable pageable) {
        return listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable);
    }

    public Page<ProviderListing> searchAll(Pageable pageable) {
        return listingRepository.findByStatus(ListingStatus.ACTIVE, pageable);
    }
}