package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public interface AvailabilityPort {
    /**
     * Read-only, relaxed availability check: is any part of the window covered
     * by a free slot and clear of time-off? Powers search/filtering.
     */
    boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt);

    /**
     * Read-only, exact availability check: does a single open slot span the
     * window exactly (the {@code bookSlot} contract)? Lets {@code BookingService.create}
     * fail a sub-window request early instead of surfacing it only at confirm
     * (codex-review-fixes-plan B4, Option M).
     */
    boolean hasExactAvailableSlot(UUID providerId, Instant startsAt, Instant endsAt);

    void bookSlot(UUID providerId, Instant startsAt, Instant endsAt);
    void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt);
}
