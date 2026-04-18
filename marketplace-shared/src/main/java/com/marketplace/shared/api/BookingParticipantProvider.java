package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port that allows other modules to look up booking participant information
 * without depending on the booking module directly.
 */
public interface BookingParticipantProvider {

    /**
     * Returns the provider ID for the given booking.
     *
     * @throws ResourceNotFoundException if the booking does not exist
     */
    UUID getProviderId(UUID bookingId);
}
