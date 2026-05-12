package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public interface AvailabilityPort {
    boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt);
}
