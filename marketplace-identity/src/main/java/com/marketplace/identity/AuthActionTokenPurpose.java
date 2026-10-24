package com.marketplace.identity;

/**
 * A-04 (official-compliance plan §6, wave A): the two purposes one
 * {@link AuthActionToken} mechanism serves. The store's own membership
 * guard is the V112 {@code auth_action_tokens_purpose_check} constraint
 * (the D-N7 discipline — the enum here is the application's single source
 * of truth, that CHECK is the store's); the names are the wire contract
 * with the migration and must never drift from it.
 */
enum AuthActionTokenPurpose {

    /** A.1 — the password-reset journey's redemption right. */
    PASSWORD_RESET,

    /** A.2 — the email-verification hold's redemption right. */
    EMAIL_VERIFICATION
}
