package com.marketplace.identity;

/**
 * Authentication audit event types.
 *
 * <p>OWASP Logging Cheat Sheet: log all authentication-related events with
 * distinct, queryable event types so security monitoring can reliably
 * distinguish them. The prior taxonomy conflated MFA failures, recovery-code
 * usage, and session revocation under generic LOGIN_FAILURE / LOGIN_SUCCESS /
 * PASSWORD_CHANGED -- making reliable alerting impossible.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html">OWASP Logging Cheat Sheet</a>
 */
public enum AuthEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    REGISTRATION,
    EMAIL_VERIFIED,
    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    ACCOUNT_LOCKED,
    ACCOUNT_UNLOCKED,
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,
    ROLE_CHANGED,
    MFA_ENABLED,
    MFA_DISABLED,
    /** Failed TOTP or recovery-code verification during step-2 login. */
    MFA_FAILURE,
    /** Successful recovery-code usage (single-use code consumed). */
    RECOVERY_CODE_USED,
    /** User logged out (explicit session termination). */
    LOGOUT,
    /** All sessions/tokens for a user revoked (e.g. "log out everywhere"). */
    SESSION_REVOKED,
    /** A specific OAuth2 authorization/token was revoked. */
    TOKEN_REVOKED,
    /** Successful OAuth2 social login (Google, GitHub, etc.). */
    OAUTH2_LOGIN_SUCCESS
}
