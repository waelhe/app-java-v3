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

    /**
     * Creates a listing priced in the given ISO 4217 currency (roadmap B4);
     * {@code null}/blank keeps the house default SAR.
     */
    ProviderListingView create(UUID providerId, String title, String description, String category,
                               Long priceCents, String currency);

    Page<ProviderListingSummary> findAllSummaries(Pageable pageable);

    ProviderListingSummary archiveListing(UUID id, Authentication authentication);

    /**
     * L37 (realestate systems plan §5 — the featured boost): the
     * administrative shading point — sets (or clears, with a null
     * {@code until}) the listing's boost window. ADMIN-gated at the
     * service (the archiveListing family — the gate rides the security
     * context, not a method parameter, so no Authentication is carried);
     * the returned state is the response's own contract. Every call is an
     * @Audited entity UPDATE, so the Envers revision trail IS the plan's
     * criterion 4 record — the admin revisions surface reads it directly.
     * The purchase point (self-service via the internal ledger or a PSP)
     * stays behind the G-R3 gate; the behavior and the ordering work
     * without it.
     */
    com.marketplace.shared.api.ListingPromotion setListingPromotion(
            UUID id, java.time.Instant until);
}
