package com.marketplace.identity;

/**
 * Phase 1 (the unified plan §10) — the verification attestation's state
 * machine (V184's closed vocabulary, the SQL CHECK's Java-side twin):
 *
 * <pre>
 *   PENDING ──review(GRANT)──▶ GRANTED ──revoke──▶ REVOKED
 *      └─────review(REJECT)──▶ REJECTED
 * </pre>
 *
 * <p>The two post-decision outcomes are distinct on purpose (the
 * §6.6 supervision vocabulary): REJECTED is a decision about the
 * evidence; REVOKED is the withdrawal of a trust fact that HAD been
 * granted (the V178 withdraw philosophy — the row and its audit history
 * stay). No transition skips the reviewer: PENDING is the only birth
 * state, and GRANTED/REJECTED/REVOKED are terminal except where an
 * explicit transition above opens them.
 */
public enum VerificationAttestationState {
    PENDING,
    GRANTED,
    REVOKED,
    REJECTED
}
