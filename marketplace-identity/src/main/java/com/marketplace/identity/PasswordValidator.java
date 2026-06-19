package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;

import java.util.regex.Pattern;

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
 * <p>Regex patterns are pre-compiled as {@code static final Pattern} fields per the
 * {@link Pattern} class Javadoc: "A regular expression, specified as a string, must
 * first be compiled into an instance of this class." Calling {@link String#matches(String)}
 * recompiles on every invocation — wasteful on a hot path (registration, login, password change).
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#implement-proper-password-strength-controls">OWASP Password Strength Controls</a>
 * @see <a href="https://pages.nist.gov/800-63-3/sp800-63b.html#sec5">NIST SP 800-63B §5.1.1 — max 64 chars</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html">java.util.regex.Pattern Javadoc</a>
 */
public final class PasswordValidator {

    /** OWASP / NIST-recommended maximum password length. */
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int MIN_PASSWORD_LENGTH = 8;

    /** Pre-compiled patterns — compiled once, reused across all calls. */
    private static final Pattern UPPERCASE = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT = Pattern.compile(".*\\d.*");

    private PasswordValidator() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BadRequestException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters long");
        }
        if (!UPPERCASE.matcher(password).matches()) {
            throw new BadRequestException("Password must contain at least one uppercase letter");
        }
        if (!LOWERCASE.matcher(password).matches()) {
            throw new BadRequestException("Password must contain at least one lowercase letter");
        }
        if (!DIGIT.matcher(password).matches()) {
            throw new BadRequestException("Password must contain at least one digit");
        }
    }
}
