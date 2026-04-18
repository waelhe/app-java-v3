package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port that allows other modules to look up booking participant information
 * without depending on the booking module directly.
 *
 * <p><b>Design decision — synchronous interface vs. event:</b>
 * Spring Modulith's primary recommendation for cross-module interaction is
 * event publication/consumption. However, this port uses a synchronous
 * interface call because the caller ({@code ReviewsService.create()}) needs
 * the booking's provider ID <em>before</em> persisting the review. A review
 * must reference the correct provider atomically — an asynchronous event
 * cannot provide this guarantee. This is consistent with Modulith's guidance
 * that synchronous queries are acceptable when the result is needed within
 * the current operation.</p>
 *
 * @see ListingPriceProvider
 */
public interface BookingParticipantProvider {

    /**
     * Returns the provider ID for the given booking.
     *
     * @throws ResourceNotFoundException if the booking does not exist
     */
    UUID getProviderId(UUID bookingId);
}
