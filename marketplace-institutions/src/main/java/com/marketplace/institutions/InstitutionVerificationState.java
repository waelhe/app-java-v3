package com.marketplace.institutions;

/**
 * B-13 (compliance plan C.3): the institution's OWN legitimacy lifecycle
 * — the {@code MembershipVerificationState} house SHAPE as the
 * institution's specific edge (the ruling's own list: التحقق المؤسسي).
 * This is NOT the membership machinery (which stays in community per the
 * ruling's «بلا تكرار أي آلية عضوية خارج community») — it answers a
 * different question: is this REGISTRY ENTRY a legitimate institution?
 * The representative self-declares ({@code UNVERIFIED}), asks for the
 * administrative review ({@code PENDING}), and the reviewer's verdict is
 * {@code VERIFIED} (the trust mark) or {@code REJECTED} (the registry
 * row stays, the trust mark never lands) — the same state vocabulary the
 * platform's standing trust machines use, one concern per machine.
 */
public enum InstitutionVerificationState {

    /** The representative's self-declared registration — visible on the registry, no trust mark. */
    UNVERIFIED,

    /** The representative explicitly asked for the administrative review. */
    PENDING,

    /** An administrator approved the claim — the trust mark rides every public read. */
    VERIFIED,

    /** An administrator rejected the claim — the row stays (the honest registry), the mark never lands. */
    REJECTED
}
