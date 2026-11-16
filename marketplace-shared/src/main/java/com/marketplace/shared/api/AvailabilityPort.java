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
     * The provider eligibility gate for the demand path — the SAME authority
     * the claim edge ({@code bookSlot}) applies: only a VERIFIED profile
     * receives bookable demand (row 11's policy; a SUSPENDED or
     * pre-verification profile neither publishes nor claims). Added by the
     * CodeRabbit review round on #741's part 4 (2026-10-28): the booking
     * CREATE path called the pure slot facts alone, so a suspended provider
     * with a previously-open slot accepted a PENDING booking that confirm
     * could then only refuse — creation now refuses the honest 409 itself,
     * exactly like the claim edge does.
     *
     * @throws com.marketplace.shared.api.ConflictException when the provider
     *         is not currently VERIFIED
     */
    void requireEligibleProvider(UUID providerId);

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
     *
     * <p><b>Review round on the wave's rebased head — the surviving-claimant
     * transfer.</b> {@code survivingClaimantId} carries the caller's claimant
     * knowledge (the booking module owns that data): another non-deleted
     * CONFIRMED/COMPLETED booking on the same window — the legacy-duplicates
     * state V73's reconciliation resolves to a single newest owner, leaving
     * older active claimants in place. When it is non-null the hold
     * <em>transfers</em> to that claimant instead of reopening the window
     * (the window stays booked under the surviving claim), so the design's
     * own invariant — a live window with an active booking stays booked —
     * holds for BOTH cancellation orders; {@code null} means "no surviving
     * active claimant" and the owner's release reopens the window as
     * before.
     */
    void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId,
            UUID survivingClaimantId);
}
