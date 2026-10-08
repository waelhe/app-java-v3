package com.marketplace.disputes;

import java.util.UUID;

/**
 * B-06 (compliance plan 0.7 — Modulith reference/events.html): the resolve
 * decision landed — with its financial outcome. Published by
 * {@link DisputeService#resolve} on the module's exposed {@code disputes}
 * NamedInterface (additive-only registration, same as
 * {@link DisputeOpenedEvent}).
 *
 * <p>{@code refundedAmountCents} carries the EXECUTED movement (the refund
 * path's cumulative total — {@code null} for RELEASE_PROVIDER / NO_ACTION
 * decisions), so a consumer never re-derives money facts from the
 * resolution enum: the event IS the outcome.
 */
public record DisputeResolvedEvent(UUID disputeId, UUID bookingId,
                                   DisputeResolution resolution, Long refundedAmountCents) {
}
