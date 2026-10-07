package com.marketplace.community;

import java.util.UUID;

/**
 * B-17 (compliance plan C.9 — the trust &amp; verification sidecar): a
 * membership's verification was GRANTED. Published by the verdict's only
 * mover — {@code NeighborhoodMembershipService.reviewVerification} on the
 * {@code approve} direction — on the module's exposed {@code community}
 * NamedInterface, the additive-only registration the contracts ledger
 * carries (a NEW event, never a rename/move of an existing one — the
 * {@code DisputeOpenedEvent}/{@code MessageReceivedEvent} B-06/B-08
 * precedent; the record's placement in {@code shared/api} and the
 * publisher wiring ride CR-10, the designed crossing for this unit).
 *
 * <p><b>The complete-fact discipline (the {@code DisputeResolvedEvent}
 * lesson — no consumer ever re-derives):</b> the payload carries the
 * verdict's whole subject — the membership row, the member, and the
 * neighborhood — so the sidecar consumers C.9 names (search for ranking,
 * catalog for the store badge, community for the visibility ceiling)
 * receive the trust signal as a fact, never as a lookup they must
 * perform against the publisher's tables.
 *
 * <p><b>The grant's two paths (the machine's measured shape):</b> both
 * {@code PENDING -> VERIFIED} (the ordinary grant) and
 * {@code REJECTED -> VERIFIED} (the re-admission lever — the #484 review
 * round's recovery path) ARE grants of the same trust signal; the event
 * fires identically on both, carrying the resulting fact.
 *
 * <p>Published inside the reviewer's own transaction (Modulith
 * {@code reference/events.html} — the registry entry commits atomically
 * with the verdict), consumed {@code AFTER_COMMIT} in the listener's own
 * {@code REQUIRES_NEW} unit.
 */
public record MembershipVerificationGrantedEvent(
        UUID membershipId,
        UUID userId,
        UUID locationId
) {
}
