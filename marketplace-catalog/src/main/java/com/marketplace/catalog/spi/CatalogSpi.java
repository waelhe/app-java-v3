package com.marketplace.catalog.spi;

import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@NamedInterface("catalog-spi")
public interface CatalogSpi {

    /**
     * Public single-listing view: resolves only ACTIVE listings.
     * Returns a read-only {@link ProviderListingView} so consumers stay
     * decoupled from the JPA entity in the catalog module.
     */
    ProviderListingView getActiveById(UUID id);

    Page<ProviderListingView> findAll(Pageable pageable);

    ProviderListingView create(UUID providerId, String title, String description, String category, Long priceCents);

    Page<ProviderListingSummary> findAllSummaries(Pageable pageable);

    ProviderListingSummary archiveListing(UUID id, Authentication authentication);
}
