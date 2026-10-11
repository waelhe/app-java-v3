package com.marketplace.identity;

/**
 * The verification credential lifecycle (ADR-0001, plan §Phase 1) — the
 * state machine the service enforces:
 *
 * <pre>
 * PENDING ──approve──▶ APPROVED ──revoke──▶ REVOKED
 *    └──reject──▶ REJECTED          (terminal)
 * </pre>
 *
 * Every other transition is the 409 contract. {@code REVOKED} and
 * {@code REJECTED} are terminal states — a resubmission creates a NEW
 * credential row (the audit trail keeps the full history per type).
 */
public enum VerificationCredentialStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REVOKED
}
