package com.marketplace.shared.api;

import java.util.Set;
import java.util.UUID;

/**
 * The catalog module's discovery projection source — the provider-listing
 * leg of the followed-sources rail: the newest ACTIVE listings of the
 * providers the user follows (the V93 write path already announces them
 * through {@code FollowedProviderNewListingEvent}; this is the read-side
 * twin so the rail assembles from source truth, not from event replay).
 *
 * <p>House pattern ({@code CatalogSearchPort} precedent): interface in
 * shared-api, marketplace-catalog implements it, discovery injects it.
 * Only live, non-deleted, ACTIVE listings answer — the same state the
 * storefront itself would show.</p>
 */
public interface ProviderListingsPort {

    /**
     * @param providerUserIds the followed providers' USER ids (the
     *        provider_follows.provider_user_id space — A1:
     *        provider_listings.provider_id references users(id), so the
     *        join is direct)
     */
    PagedResponse<ProviderListingSummary> findActiveByProviders(Set<UUID> providerUserIds, PagedRequest page);
}
