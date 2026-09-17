package com.marketplace.community;

/**
 * The membership's residency-verification state (neighborhood community
 * plan §5-L41 / D-N3): <b>self-declared from day one, schema-only</b>.
 *
 * <p>{@code VERIFIED} is <em>reserved behind gate G-N2</em> (the
 * verification method — postal card, SMS, neighbor vouching, or nothing —
 * is a product decision with a potential external provider): it does not
 * exist in this vocabulary yet, and the V60 CHECK pins the column to
 * {@code SELF_DECLARED} so no writer — entity or raw SQL — can invent a
 * verification before its gate opens. Opening the gate is a deliberate
 * three-part change: the vocabulary gains the value, a new migration
 * widens the CHECK, and the verification machinery itself arrives with
 * its own layer. "لا اختراع تحقق قبل بوابته" — no invented verification
 * before its gate.
 */
public enum MembershipVerificationState {

    /**
     * The member declared this neighborhood themselves; nobody confirmed
     * anything. The only state the anchor layer can produce.
     */
    SELF_DECLARED
}
