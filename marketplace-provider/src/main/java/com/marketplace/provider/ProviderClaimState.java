package com.marketplace.provider;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the business
 * claim's state machine. Four states in the {@link ProviderVerificationState}
 * shape (this module's own measured precedent):
 *
 * <ul>
 *   <li>{@link #PENDING} — a member submitted the claim (born here — the
 *       factory's only state); exactly ONE live PENDING claim per page
 *       (the V233 partial unique index {@code uq_provider_claims_one_live},
 *       the register's «بناء الدورة بمالك واحد» — one owner at a time,
 *       one claim in flight at a time);</li>
 *   <li>{@link #APPROVED} — an administrator confirmed the claim: the
 *       ownership handover landed (provider_profiles.user_id = claimant,
 *       claimed_at set — the J14 acceptance's measured proof);</li>
 *   <li>{@link #REJECTED} — an administrator declined the claim; the
 *       claimant may submit a FRESH claim (a new PENDING row — the
 *       live-claim index no longer blocks, the Envers trail keeps the
 *       history);</li>
 *   <li>{@link #WITHDRAWN} — the claimant took their own claim back
 *       (the recovery lever: the page is claimable again the moment
 *       this lands).</li>
 * </ul>
 *
 * <p>The decided states are terminal — every decision on a decided claim
 * answers the machine's own {@link IllegalStateException} (the caller
 * renders the house 409). The allowed transitions:
 * {@code PENDING → APPROVED / REJECTED / WITHDRAWN}. Every other pair is
 * rejected loudly.
 */
public enum ProviderClaimState {

    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    /**
     * The transition law — validated before assignment (the
     * {@link ProviderVerificationState#validateTransitionTo} pattern this
     * module already applies to its verification lifecycle).
     */
    public void validateTransitionTo(ProviderClaimState target) {
        if (this != PENDING || target == null || target == PENDING) {
            throw new IllegalStateException(
                    "provider claim state " + this + " cannot move to " + target);
        }
    }
}
