package com.marketplace.catalog.spi;

import com.marketplace.catalog.ProviderListing;
import com.marketplace.shared.api.ProviderListingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@NamedInterface("catalog-spi")
public interface CatalogSpi {

    ProviderListing getById(UUID id);

    Page<ProviderListing> findAll(Pageable pageable);

    ProviderListing create(UUID providerId, String title, String description, String category, Long priceCents);

    Page<ProviderListingSummary> findAllSummaries(Pageable pageable);

    ProviderListingSummary archiveListing(UUID id, Authentication authentication);
}
