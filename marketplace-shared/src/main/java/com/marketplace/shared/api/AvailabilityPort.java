package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public interface AvailabilityPort {
    void requireAvailable(UUID providerId, UUID listingId, Instant startsAt, Instant endsAt);
}
