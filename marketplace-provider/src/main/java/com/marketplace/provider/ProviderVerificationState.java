package com.marketplace.provider;

/**
 * W2 (yelp-level plan §5 — the business page): the provider ownership
 * verification lifecycle — the Yelp «مالك موثّق» (Verified Owner) badge's
 * state machine. Four states in the V78 lifecycle shape (the closest
 * measured precedent in the tree):
 *
 * <ul>
 *   <li>{@link #UNVERIFIED} — the honest default every existing profile
 *       keeps (V89's backfill; zero visible behavior change — the W0
 *       seed-mode rule applied to this wave's own column);</li>
 *   <li>{@link #PENDING} — the owner submitted a verification claim
 *       (the license text the profile may already carry is the claim's
 *       evidence — V56 declared it display-only, and this state machine
 *       keeps that honesty: the claim is queued, not believed);</li>
 *   <li>{@link #VERIFIED} — an administrator confirmed ownership; the
 *       badge lights on the public page;</li>
 *   <li>{@link #REJECTED} — an administrator declined the claim; the
 *       owner may submit again (a fresh PENDING leg of the Envers
 *       history — the trail is the record).</li>
 * </ul>
 *
 * <p><b>Display-only trust signal, exactly like {@code license_number}
 * (V56):</b> no privilege attaches to {@link #VERIFIED} — the profile's
 * existing {@link ProviderStatus} lifecycle stays the gate for listings
 * and page inventory. The badge answers one question the page's visitors
 * ask («هل هذا المالك مؤكد؟»), nothing else.
 *
 * <p>The allowed transitions (validated in the entity, CHECKed in V89):
 * {@code UNVERIFIED → PENDING}, {@code PENDING → VERIFIED},
 * {@code PENDING → REJECTED}, {@code REJECTED → PENDING} (resubmission),
 * {@code VERIFIED → PENDING} (re-verification, e.g. after ownership
 * change). Every other pair is rejected loudly.
 */
public enum ProviderVerificationState {

    UNVERIFIED,
    PENDING,
    VERIFIED,
    REJECTED;

    /**
     * The transition law — a small explicit adjacency table, validated
     * before assignment (the {@code ProviderStatus.validateTransitionTo}
     * pattern this module already applies to its status lifecycle).
     */
    public void validateTransitionTo(ProviderVerificationState target) {
        if (target == null || !canTransitionTo(target)) {
            throw new IllegalStateException(
                    "provider verification state " + this + " cannot move to " + target);
        }
    }

    private boolean canTransitionTo(ProviderVerificationState target) {
        return switch (this) {
            case UNVERIFIED -> target == PENDING;
            case PENDING -> target == VERIFIED || target == REJECTED;
            case VERIFIED -> target == PENDING; // re-verification after ownership change
            case REJECTED -> target == PENDING; // the owner's corrected resubmission
        };
    }
}
