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

    /**
     * R2 (comprehensive-review-ar-fix plan §4/R2 — slot ownership): books the
     * exact window's open slot IN THE NAME of {@code bookingId} — the
     * confirming booking claims the hold (booked flag and owner are set
     * together, the entity's {@code @Version} optimistic lock settles
     * concurrent claims on the same row). Callers pass the booking's own id
     * at confirm/autoConfirm time; a {@code ConflictException} means the
     * window has no open slot (already held by another booking).
     */
    void bookSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId);

    /**
     * R2: releases the window's hold — and ONLY the hold this booking itself
     * placed. The slot is freed when its {@code heldByBookingId} equals
     * {@code bookingId}; a release carried by any other booking (the
     * PENDING sibling of the holder) is a no-op by contract, which is what
     * closes the review's measured finding (a non-owner cancel used to free
     * the slot a CONFIRMED booking owned).
     */
    void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId);
}
