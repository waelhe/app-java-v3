package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port that allows other modules to look up listing pricing information
 * without depending on the catalog module directly.
 */
public interface ListingPriceProvider {

    /**
     * Returns the provider ID and price for the given listing.
     *
     * @throws ResourceNotFoundException if the listing does not exist
     */
    ListingInfo getListingInfo(UUID listingId);

    /**
     * Carrier of server-derived listing data.
     * Immutable value object — no behaviour.
     */
    record ListingInfo(UUID providerId, Long priceCents) {}
}
