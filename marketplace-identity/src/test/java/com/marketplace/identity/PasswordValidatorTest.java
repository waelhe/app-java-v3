package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PasswordValidator} -- OWASP password strength policy.
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication</a>
 */
class PasswordValidatorTest {

    @Test
    void validate_acceptsStrongPassword() {
        assertDoesNotThrow(() -> PasswordValidator.validate("SecurePass123"));
    }

    @Test
    void validate_rejectsShortPassword() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PasswordValidator.validate("Ab1"));
        assertTrue(ex.getMessage().contains("at least 8 characters"));
    }

    @Test
    void validate_rejectsMissingUppercase() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PasswordValidator.validate("lowercase123"));
        assertTrue(ex.getMessage().contains("uppercase"));
    }

    @Test
    void validate_rejectsMissingLowercase() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PasswordValidator.validate("UPPERCASE123"));
        assertTrue(ex.getMessage().contains("lowercase"));
    }

    @Test
    void validate_rejectsMissingDigit() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PasswordValidator.validate("NoDigitsHere"));
        assertTrue(ex.getMessage().contains("digit"));
    }

    @Test
    void validate_rejectsNullPassword() {
        assertThrows(BadRequestException.class, () -> PasswordValidator.validate(null));
    }

    @Test
    void validate_rejectsTooLongPassword() {
        // 65 chars -- exceeds OWASP/NIST max of 64.
        String tooLong = "Aa1" + "x".repeat(62);
        assertEquals(65, tooLong.length());
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PasswordValidator.validate(tooLong));
        assertTrue(ex.getMessage().contains("at most 64 characters"),
                "Must reject passwords > 64 chars (OWASP / NIST SP 800-63B): " + ex.getMessage());
    }

    @Test
    void validate_acceptsExactly64CharPassword() {
        // Exactly 64 chars -- boundary check (must pass).
        String exact = "Aa1" + "x".repeat(61);
        assertEquals(64, exact.length());
        assertDoesNotThrow(() -> PasswordValidator.validate(exact));
    }
}
