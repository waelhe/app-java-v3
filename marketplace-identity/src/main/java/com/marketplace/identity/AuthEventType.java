package com.marketplace.identity;

/**
 * Authentication audit event types.
 * <p>OWASP recommendation: log all authentication-related events.
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
    ACCOUNT_ENABLED
}
