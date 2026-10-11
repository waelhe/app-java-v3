package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-06 (compliance plan 0.7 — Modulith reference/events.html): a dispute
 * entered the pipeline. Published by {@code DisputeService#open} on the
 * module's exposed {@code disputes} NamedInterface — the event vocabulary
 * the notifications/audit consumers can subscribe to (the additive-only
 * registration: a NEW event, never a rename/move of an existing one —
 * pending the contracts ledger the foundation branch will carry).
 *
 * <p>The payload is the house's lean event shape (business ids only —
 * {@code BookingCreatedEvent}/{@code ListingLeadCreatedEvent} pattern):
 * the dispute row, the disputed booking, and who opened it.
 *
 * <p><b>Relocated to shared-api per the events-through-Modulith rule</b>
 * (the contracts ledger §1.1 own ruling for this record: "عند أول مستهلك
 * عبر الحدود: ينتقل السجل إلى shared/api" — the first cross-boundary
 * consumer arriving is this commit's notifications listener, so the
 * module-local record moves here, the {@code MessageReceivedEvent} CR-4
 * flow verbatim: package declaration only, byte-equivalent payload, no
 * pom change anywhere).</p>
 *
 * @param disputeId the dispute's id
 * @param bookingId the disputed booking's id
 * @param openedBy  the disputing party's user id
 */
public record DisputeOpenedEvent(UUID disputeId, UUID bookingId, UUID openedBy) {
}
