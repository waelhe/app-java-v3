package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TotpService} — RFC 6238 TOTP implementation.
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238</a>
 */
class TotpServiceTest {

    @Test
    void generateSecret_returnsBase64String() {
        String secret = TotpService.generateSecret();
        assertNotNull(secret);
        assertFalse(secret.isBlank());
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(secret));
    }

    @Test
    void generateSecret_returnsDifferentSecretsEachCall() {
        String s1 = TotpService.generateSecret();
        String s2 = TotpService.generateSecret();
        assertNotEquals(s1, s2);
    }

    @Test
    void validateCode_returnsFalseForNullCode() {
        String secret = TotpService.generateSecret();
        assertFalse(TotpService.validateCode(secret, null));
    }

    @Test
    void validateCode_returnsFalseForWrongLength() {
        String secret = TotpService.generateSecret();
        assertFalse(TotpService.validateCode(secret, "12345"));
        assertFalse(TotpService.validateCode(secret, "1234567"));
    }

    @Test
    void validateCode_returnsFalseForWrongCode() {
        String secret = TotpService.generateSecret();
        assertFalse(TotpService.validateCode(secret, "000000"));
    }

    @Test
    void buildOtpAuthUri_containsRequiredParts() {
        String secret = TotpService.generateSecret();
        String uri = TotpService.buildOtpAuthUri(secret, "user@test.com", "Marketplace");

        assertNotNull(uri);
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("Marketplace:user@test.com"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }
}
