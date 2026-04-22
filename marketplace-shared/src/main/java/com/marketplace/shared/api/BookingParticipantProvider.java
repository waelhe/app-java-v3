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
 * booking details <em>before</em> persisting the review. This is consistent
 * with Modulith's guidance that synchronous queries are acceptable when the
 * result is needed within the current operation.</p>
 *
 * @see ListingPriceProvider
 */
public interface BookingParticipantProvider {

    /**
     * Returns booking participant and lifecycle information.
     *
     * @throws ResourceNotFoundException if the booking does not exist
     */
    BookingInfo getBookingInfo(UUID bookingId);

    /**
     * Backward-compatible convenience accessor.
     */
    @Deprecated
    default UUID getProviderId(UUID bookingId) {
        return getBookingInfo(bookingId).providerId();
    }
}
