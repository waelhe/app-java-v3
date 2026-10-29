package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Row 15 (agent findings 2026-10-25) — the port that lets the lending
 * module read the disputes module's OPEN-state facts without depending on
 * it: the {@code LoanPartyProvider} twin in the REVERSE direction
 * (ADR-0009's module-contract pair — the data owner implements, the
 * consumer asks; no module imports a sibling). The loan's dispute freeze
 * is a fact about OPEN disputes, so BOTH of its edges ask the owner:
 * the freeze applies only while the opening dispute is still open (a
 * redelivered open event for an already-resolved dispute must not
 * re-freeze a resumed loan — a freeze no future resolution would ever
 * release), and the release fires only when NO other open dispute still
 * requires the freeze (resolving ONE of several concurrent disputes must
 * not resume the loan under the others, which is what used to silently
 * discard a later approved refund).
 *
 * <p><b>Design decision — synchronous interface vs. event:</b> the same
 * reasoning as the {@code LoanPartyProvider} twin's own javadoc — the
 * freeze/release listener needs the open-state fact <em>before</em> its
 * guarded write, inside the same listener transaction, so a synchronous
 * query is the official shape. The AFTER_COMMIT/REQUIRES_NEW
 * module-listener semantics (the Modulith events contract) guarantee the
 * publishing transaction's dispute state is committed and visible when
 * the question is asked.
 */
public interface DisputeLookupPort {

    /**
     * Whether the dispute is still in the OPEN state — the freeze edge's
     * truth: a freeze is applied only for a dispute that still requires
     * it.
     */
    boolean isDisputeOpen(UUID disputeId);

    /**
     * Whether the loan still has at least one OPEN dispute after
     * {@code excludingDisputeId} is discounted — the release edge's
     * truth: the freeze is released only when no open dispute remains to
     * hold it. The exclusion is dispute-identity idempotency (row 15's
     * second half): the resolution of dispute X must never be held
     * hostage by X's own row, whatever the delivery mode.
     */
    boolean hasOpenDisputeForLoan(UUID loanId, UUID excludingDisputeId);

    /**
     * Row 8 (agent findings 2026-10-26 14:33 export): whether ANY of the
     * loan's disputes was resolved to the consumer's refund — the release
     * edge's terminal-decision fact. A refund decision already committed
     * (its event possibly still awaiting consumption through the
     * publication registry's resubmission) must hold the freeze: releasing
     * the loan first would drop the approved refund on arrival, because
     * the refund branch accepts the loan in its frozen state. An APPLIED
     * refund needs no hold — the loan is already terminal (CANCELLED) and
     * the DISPUTED guard never reaches the release branch.
     */
    boolean hasRefundResolutionForLoan(UUID loanId);
}
