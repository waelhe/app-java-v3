package com.marketplace.identity;

/**
 * The verification credential vocabulary (ADR-0001, plan §Phase 1) — the
 * five evidence types an account may submit. A credential is EVIDENCE about
 * the account; it is never authority, and deciding it never writes a role
 * (the ADR-0001 separation: identity ≠ roles ≠ credentials).
 */
public enum VerificationCredentialType {
    /** Who the account's human is (government-issued identity evidence). */
    IDENTITY,
    /** Where the member lives — the community-locality evidence. */
    RESIDENCE,
    /** Ownership of the business the account trades behind. */
    BUSINESS_OWNERSHIP,
    /** A professional qualification the member claims to practice with. */
    PROFESSIONAL_QUALIFICATION,
    /** The right to publish official messages (§6 — the official-broadcast trust). */
    OFFICIAL_PUBLISHER
}
