package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-06 (compliance plan 0.7 — Modulith reference/events.html): the resolve
 * decision landed — with its financial outcome. Published by
 * {@code DisputeService#resolve} on the module's exposed {@code disputes}
 * NamedInterface (additive-only registration, same as
 * {@link DisputeOpenedEvent}).
 *
 * <p>{@code refundedAmountCents} carries the EXECUTED movement (the refund
 * path's cumulative total — {@code null} for RELEASE_PROVIDER / NO_ACTION
 * decisions), so a consumer never re-derives money facts from the
 * resolution enum: the event IS the outcome.
 *
 * <p><b>Relocated to shared-api per the events-through-Modulith rule</b>
 * (the contracts ledger §1.1 own ruling, the
 * {@code DisputeOpenedEvent} relocation of this same commit — package
 * declaration only, no pom change anywhere).</p>
 *
 * <p><b>The complete-fact discipline (the {@code MessageReceivedEvent}
 * rule this record's own lack of the opener taught the house — the
 * {@code MembershipVerificationGrantedEvent} javadoc cites it as "the
 * DisputeResolvedEvent lesson"):</b> {@code openedBy} joins the payload
 * AT the relocation moment — the dispute row holds the fact
 * ({@code disputes.opened_by}), the publisher has it in hand, and the
 * first consumer (the notifications dispute listener) delivers to it
 * without re-deriving any party fact. No measured consumer existed to
 * break (the module-local record had ZERO listeners — the measured
 * defect this wave closes), so the payload lands complete from the
 * shared contract's first day.</p>
 *
 * <p><b>The vocabulary is carried, not shared (the
 * {@code ContentReportResolvedEvent} String precedent):</b>
 * {@code resolution} rides as the STORED name
 * ({@code "REFUND_CONSUMER"/"RELEASE_PROVIDER"/"NO_ACTION"} — the
 * {@code DisputeResolution} vocabulary, pinned DB-side by V38) so the
 * record stays free of disputes-domain enum types.</p>
 *
 * @param disputeId           the resolved dispute's id
 * @param bookingId           the disputed booking's id
 * @param openedBy            the disputing party's user id (the notification's recipient)
 * @param resolution          the decision's stored name
 * @param refundedAmountCents the EXECUTED refunded total ({@code null} when no money moved)
 */
public record DisputeResolvedEvent(UUID disputeId, UUID bookingId, UUID openedBy,
                                   String resolution, Long refundedAmountCents) {
}
