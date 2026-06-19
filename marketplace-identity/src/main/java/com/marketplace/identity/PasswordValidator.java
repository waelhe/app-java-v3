package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;

/**
 * Password policy validator following OWASP Authentication Cheat Sheet.
 *
 * <p>Requires:
 * <ul>
 *   <li>Minimum 8 characters</li>
 *   <li>At least one uppercase letter</li>
 *   <li>At least one lowercase letter</li>
 *   <li>At least one digit</li>
 *   <li><b>Maximum 64 characters</b> (OWASP / NIST SP 800-63B §5.1.1) — BCrypt silently
 *       truncates input beyond 72 bytes, so two passwords sharing the first 72 bytes
 *       hash identically. Capping at 64 chars avoids both the truncation surprise
 *       and the CPU-burn DoS vector from very long inputs.</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#implement-proper-password-strength-controls">OWASP Password Strength Controls</a>
 * @see <a href="https://pages.nist.gov/800-63-3/sp800-63b.html#sec5">NIST SP 800-63B §5.1.1 — max 64 chars</a>
 */
public final class PasswordValidator {

    /** OWASP / NIST-recommended maximum password length. */
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int MIN_PASSWORD_LENGTH = 8;

    private PasswordValidator() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BadRequestException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters long");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new BadRequestException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new BadRequestException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new BadRequestException("Password must contain at least one digit");
        }
    }
}
