package com.marketplace.catalog.spi;

import com.marketplace.catalog.ListingStatus;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingsPort;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JT-20 (#536 discovery waves D1-D4): the catalog module's implementation
 * of the {@link ProviderListingsPort} cross-module read contract — the
 * followed-sources rail's provider-listing leg — on the
 * {@code CommunityDiscoveryAdapter} house shape (the interface lives in
 * shared-api, the data owner implements it, the consumer injects it —
 * no cross-module repository access).
 *
 * <p><b>Eligibility-first, deterministically (AC-20):</b> the query gates
 * on ACTIVE — the storefront's own state: the expiry job owns the
 * ACTIVE/EXPIRED boundary, so an ad-hoc expiry predicate here would fork
 * the storefront's semantics. Hibernate's {@code @SoftDelete} filter
 * answers the non-deleted half of the contract without a predicate of
 * its own (the {@code CommunityDiscoveryAdapter} discipline verbatim).
 * The adapter pins the rail's deterministic order itself — newest first
 * with the id tiebreak (the D-N5 complete sort key, no shaky pages): the
 * port's contract fixes the order ("newest" is IN the contract), so a
 * caller sort is not part of the vocabulary; the {@link SpringPagination}
 * conversion carries the page/size only.
 *
 * <p><b>Honest-empty:</b> an empty (or null) provider set answers an
 * empty page WITHOUT touching the database — an empty IN clause is a
 * query bug, never a valid read (the house empty-guard stance).
 */
@Component
@Transactional(readOnly = true)
public class ProviderListingsAdapter implements ProviderListingsPort {

    /** The rail's order — newest first, id tiebreak (D-N5), the {@code CommunityDiscoveryAdapter} RECENCY_SORT discipline. */
    private static final Sort RECENCY_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final ProviderListingRepository repository;

    public ProviderListingsAdapter(ProviderListingRepository repository) {
        this.repository = repository;
    }

    @Override
    public PagedResponse<ProviderListingSummary> findActiveByProviders(Set<UUID> providerUserIds, PagedRequest page) {
        if (providerUserIds == null || providerUserIds.isEmpty()) {
            return PagedResponse.of(new PageImpl<>(List.of(), PageRequest.of(page.page(), page.size()), 0));
        }
        return PagedResponse.of(repository
                .findByProviderIdInAndStatus(providerUserIds, ListingStatus.ACTIVE,
                        PageRequest.of(page.page(), page.size(), RECENCY_SORT))
                .map(ProviderListingsAdapter::toSummary));
    }

    /**
     * The verbatim row mapping — the S7 discipline: {@code price} and its
     * ISO 4217 {@code currency} travel together
     * ({@code BigDecimal.valueOf(priceCents, 2)}, the {@code ListingMapper}
     * expression verbatim), and the status travels as its enum's name so
     * the card renders the storefront's own state truthfully.
     */
    private static ProviderListingSummary toSummary(ProviderListing listing) {
        return new ProviderListingSummary(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                BigDecimal.valueOf(listing.getPriceCents(), 2),
                listing.getCurrency(),
                listing.getProviderId(),
                listing.getStatus().name(),
                listing.getCreatedAt(),
                listing.getUpdatedAt());
    }
}
