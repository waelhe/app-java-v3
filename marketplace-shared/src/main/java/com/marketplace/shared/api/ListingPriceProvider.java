package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port that allows other modules to look up listing pricing information
 * without depending on the catalog module directly.
 *
 * <p><b>Design decision — synchronous interface vs. event:</b>
 * Spring Modulith's primary recommendation for cross-module interaction is
 * event publication/consumption. However, this port uses a synchronous
 * interface call because the caller ({@code BookingService.create()}) needs
 * the listing's price and provider <em>before</em> persisting the booking.
 * An asynchronous event cannot satisfy this requirement — the booking must
 * be created with the correct price atomically within the same transaction.
 * This follows the Modulith guidance that synchronous queries are acceptable
 * when the result is needed <em>within</em> the current operation.</p>
 *
 * @see BookingParticipantProvider
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
