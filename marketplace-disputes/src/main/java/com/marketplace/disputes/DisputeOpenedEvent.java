package com.marketplace.disputes;

import java.util.UUID;

/**
 * B-06 (compliance plan 0.7 — Modulith reference/events.html): a dispute
 * entered the pipeline. Published by {@link DisputeService#open} on the
 * module's exposed {@code disputes} NamedInterface — the event vocabulary
 * the notifications/audit consumers can subscribe to (the additive-only
 * registration: a NEW event, never a rename/move of an existing one —
 * pending the contracts ledger the foundation branch will carry).
 *
 * <p>The payload is the house's lean event shape (business ids only —
 * {@code BookingCreatedEvent}/{@code ListingLeadCreatedEvent} pattern):
 * the dispute row, the disputed booking, and who opened it.
 */
public record DisputeOpenedEvent(UUID disputeId, UUID bookingId, UUID openedBy) {
}
