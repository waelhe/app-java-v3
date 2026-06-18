package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PasswordValidator} — OWASP password strength policy.
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
}
