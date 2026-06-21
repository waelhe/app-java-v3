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

    /**
     * RFC 4648 compliance: the secret in the otpauth URI MUST be Base32-encoded
     * so that Google/Microsoft Authenticator apps can decode it.
     *
     * @see <a href="https://github.com/google/google-authenticator/wiki/Key-Uri-Format">Google Authenticator Key URI Format</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc4648">RFC 4648</a>
     */
    @Test
    void buildOtpAuthUri_secretIsBase32Encoded() {
        String secret = TotpService.generateSecret();
        String uri = TotpService.buildOtpAuthUri(secret, "user@test.com", "Marketplace");

        // Extract secret=... from the URI
        String secretParam = java.util.regex.Pattern.compile("secret=([^&]+)")
                .matcher(uri).results().findFirst().map(m -> m.group(1)).orElseThrow();
        // RFC 4648 Base32 alphabet: A-Z, 2-7, plus optional = padding
        assertTrue(secretParam.matches("[A-Z2-7]+=?=?"),
                "Secret must be Base32 (RFC 4648): " + secretParam);
        // Decoding back to raw bytes must match the original secret's raw bytes
        byte[] rawFromBase32 = new org.apache.commons.codec.binary.Base32().decode(secretParam);
        byte[] rawFromBase64 = java.util.Base64.getDecoder().decode(secret);
        assertArrayEquals(rawFromBase64, rawFromBase32,
                "Base32-decoded secret must equal Base64-decoded internal secret");
    }

    /**
     * The otpauth URI must encode the *same* key material as the internal Base64 secret.
     * A round-trip: generate secret → build URI → extract Base32 secret →
     * derive the same TOTP code → validate against the original secret.
     */
    @Test
    void buildOtpAuthUri_roundTripsToSameSecret() {
        String secret = TotpService.generateSecret();
        String uri = TotpService.buildOtpAuthUri(secret, "user@test.com", "Marketplace");

        String base32Secret = java.util.regex.Pattern.compile("secret=([^&]+)")
                .matcher(uri).results().findFirst().map(m -> m.group(1)).orElseThrow();
        // Reconstruct a Base64-encoded secret from the Base32 (what an authenticator would have)
        byte[] rawKey = new org.apache.commons.codec.binary.Base32().decode(base32Secret);
        String reconstructedBase64 = java.util.Base64.getEncoder().encodeToString(rawKey);

        assertEquals(secret, reconstructedBase64,
                "The secret encoded in the URI must equal the original internal secret");
    }
}
