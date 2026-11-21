package com.marketplace.identity;

/**
 * A-04 (official-compliance plan §6, wave A): the two purposes one
 * {@link AuthActionToken} mechanism serves. The store's own membership
 * guard is the V112 {@code auth_action_tokens_purpose_check} constraint
 * (the D-N7 discipline — the enum here is the application's single source
 * of truth, that CHECK is the store's); the names are the wire contract
 * with the migration and must never drift from it.
 *
 * <p>Agent 1 (the Saleor 458-root campaign, assignment packet PR #696):
 * the vocabulary was widened by V251 for the account lifecycle's two new
 * redemption rights — {@link #EMAIL_CHANGE} (the requestEmailChange/
 * confirmEmailChange journey) and {@link #ACCOUNT_DELETION} (the
 * accountRequestDeletion/accountDelete journey). Same mechanism, same
 * single-use TTL redemption semantics; the CHECK now pins all four.</p>
 */
enum AuthActionTokenPurpose {

    /** A.1 — the password-reset journey's redemption right. */
    PASSWORD_RESET,

    /** A.2 — the email-verification hold's redemption right. */
    EMAIL_VERIFICATION,

    /** Agent 1 — the email-change journey's redemption right (V251). */
    EMAIL_CHANGE,

    /** Agent 1 — the account self-deletion journey's redemption right (V251). */
    ACCOUNT_DELETION
}
