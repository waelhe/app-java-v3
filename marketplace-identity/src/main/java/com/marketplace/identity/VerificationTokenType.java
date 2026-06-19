package com.marketplace.identity;

/**
 * Type of verification token.
 * <p>Follows OWASP Authentication Cheat Sheet:
 * <ul>
 *   <li>{@code EMAIL_VERIFICATION} -- 24h expiry</li>
 *   <li>{@code PASSWORD_RESET} -- 30min expiry</li>
 * </ul>
 */
public enum VerificationTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
