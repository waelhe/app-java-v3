package com.marketplace.catalog.spi;

import com.marketplace.shared.api.ProviderListingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * SPI for cross-module access to catalog operations.
 * Admin and future modules depend on this interface, not on CatalogService directly.
 */
@NamedInterface("catalog-spi")
public interface CatalogSpi {

    Page<ProviderListingSummary> findAllSummaries(Pageable pageable);

    ProviderListingSummary archiveListing(UUID id, Authentication authentication);
}
