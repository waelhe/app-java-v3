package com.marketplace.knowledge;

/**
 * D-3 (JT-19/D-30 — «أخبار محلية»): the news publisher's OWN legitimacy
 * lifecycle — the {@code InstitutionVerificationState} house SHAPE with
 * the V154 quadruple verbatim ({@code UNVERIFIED/PENDING/VERIFIED/
 * REJECTED}, the same vocabulary the DB CHECK carries byte-for-byte, the
 * D-N7 two-sided discipline). This is NOT the institutions machinery —
 * it answers a different question: is THIS OUTLET a legitimate news
 * publisher? The admin registers the publisher (born {@code UNVERIFIED}
 * — the honest registry) and is the verdict's ONLY mover: APPROVE lands
 * the trust mark ({@code VERIFIED}) and re-admits a {@code REJECTED}
 * one, REJECT refuses the claim (the row stays). {@code PENDING} exists
 * in the vocabulary for the future self-service registration flow (the
 * widening discipline: a future state rides a migration, never a
 * rebuild). A {@code VERIFIED} publisher is the ONE state that may
 * publish news items — the same delegated-source gate the urgent-alert
 * wave rides.
 */
public enum NewsPublisherState {

    /** The admin's registered outlet — visible to the admin, no trust mark, cannot publish news. */
    UNVERIFIED,

    /** Reserved for the future self-service registration flow (the V154 widening discipline). */
    PENDING,

    /** An administrator approved the outlet — the trust mark; the ONLY state that may publish news items. */
    VERIFIED,

    /** An administrator rejected the claim — the row stays (the honest registry), the mark never lands. */
    REJECTED
}
