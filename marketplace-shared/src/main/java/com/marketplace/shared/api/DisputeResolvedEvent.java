package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The resolve decision landed — with its financial outcome (B-06
 * introduced it; ADR-0009 moved it to {@code shared/api} — the parallel
 * contracts ledger's own rule, the first cross-boundary consumer being the
 * lending module's resolution listener).
 *
 * <p>{@code refundedAmountCents} carries the EXECUTED movement for a
 * BOOKING-subject REFUND_CONSUMER decision (the refund path's cumulative
 * total — {@code null} otherwise), so a consumer never re-derives money
 * facts from the resolution enum: the event IS the outcome. For a LOAN
 * subject the money never moves here — the REFUND_CONSUMER decision rides
 * the lending module's own cancellation chain ({@link LoanCancelledEvent}
 * → the payments module's listener), the ONE refund contract.
 */
public record DisputeResolvedEvent(UUID disputeId, DisputeSubject subjectType,
                                   UUID bookingId, UUID loanId,
                                   DisputeResolution resolution, Long refundedAmountCents) {
}
