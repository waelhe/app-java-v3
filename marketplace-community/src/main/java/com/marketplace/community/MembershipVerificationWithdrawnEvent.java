package com.marketplace.community;

import java.util.UUID;

/**
 * B-17 (compliance plan C.9 — the trust &amp; verification sidecar): a
 * membership's verification was WITHDRAWN. Published by the verdict's
 * only mover — {@code NeighborhoodMembershipService.reviewVerification}
 * on the {@code reject} direction — on the module's exposed
 * {@code community} NamedInterface, the additive-only registration the
 * contracts ledger carries (a NEW event; the record's placement in
 * {@code shared/api} and the publisher wiring ride CR-10, the designed
 * crossing for this unit).
 *
 * <p><b>What the withdrawal IS (the machine's measured semantics):</b>
 * the {@code REJECTED} verdict is the one admin decision that pulls the
 * member's standing down — {@code MembershipVerificationState.REJECTED}
 * denies community writes and new direct chats
 * ({@code NeighborhoodMembership.mayUseCommunityWrites}), and the
 * verdict follows the user across a leave-and-rejoin
 * ({@code inheritRejectedVerdict}). The withdrawal event carries that
 * signal's arrival as a fact so the sidecar consumers C.9 names receive
 * the trust drop exactly as they receive the grant — never by polling
 * the publisher's state.
 *
 * <p>The complete-fact discipline ({@code MembershipVerificationGrantedEvent}'s
 * own shape): the membership row, the member, and the neighborhood — no
 * consumer re-derives.
 *
 * <p>Published inside the reviewer's own transaction (Modulith
 * {@code reference/events.html}), consumed {@code AFTER_COMMIT} in the
 * listener's own {@code REQUIRES_NEW} unit.
 */
public record MembershipVerificationWithdrawnEvent(
        UUID membershipId,
        UUID userId,
        UUID locationId
) {
}
