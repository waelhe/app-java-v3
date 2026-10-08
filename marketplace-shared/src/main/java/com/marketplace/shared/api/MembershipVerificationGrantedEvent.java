package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-17 (compliance plan C.9 — the trust &amp; verification sidecar): a
 * membership's verification was GRANTED. Published by the verdict's only
 * mover — {@code NeighborhoodMembershipService.reviewVerification} on the
 * {@code approve} direction — on the module's exposed {@code community}
 * NamedInterface, the additive-only registration the contracts ledger
 * carries (a NEW event, never a rename/move of an existing one — the
 * {@code DisputeOpenedEvent}/{@code MessageReceivedEvent} B-06/B-08
 * precedent).
 *
 * <p><b>Placement ruling (the CR-4 crossing, landed by the CodeRabbit
 * round-1 adoption — the contracts ledger §1.1 house convention
 * measured on every cross-boundary record the notifications listener
 * consumes):</b> this event crosses module boundaries, so its record
 * lives in {@code shared/api}. The consumer-side import needs NO new
 * module dependency this way: notifications already depends on shared,
 * and no pom anywhere changes. The record was moved byte-equivalent
 * from the community module (package declaration only — the
 * {@code MessageReceivedEvent} CR-4 flow verbatim).
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
