package com.marketplace.community;

/**
 * The membership's residency-verification state (neighborhood community
 * plan §5-L41): the single membership trust lifecycle. The initial verifier
 * is a manual administrative review; an external provider remains behind
 * the user's G-N2 decision.
 */
public enum MembershipVerificationState {
    /** Existing self-declared memberships migrate here; baseline access remains intact. */
    UNVERIFIED,
    /** The member explicitly asked for an administrator to review their residency claim. */
    PENDING,
    /** An administrator approved the claim. External-provider evidence remains behind G-N2. */
    VERIFIED,
    /** An administrator rejected the claim; community writes and new direct chats are denied. */
    REJECTED
}
