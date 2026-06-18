package com.marketplace.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP (Time-Based One-Time Password) implementation.
 * <p>Pure Java implementation of RFC 6238 — no external dependencies.
 * Uses HMAC-SHA1 (compatible with Google Authenticator, Microsoft Authenticator, etc.)
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc4226">RFC 4226 (HOTP)</a>
 */
public final class TotpService {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int SECRET_BYTES = 20; // 160-bit secret (RFC 4226 §4)

    private TotpService() {
    }

    /**
     * Generates a random Base64-encoded TOTP secret.
     *
     * @return Base64-encoded secret
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Validates a TOTP code against the secret.
     * Allows a window of ±1 time step (±30 seconds) for clock drift.
     *
     * @param secret Base64-encoded secret
     * @param code   6-digit code from authenticator app
     * @return true if valid
     */
    public static boolean validateCode(String secret, String code) {
        if (code == null || code.length() != CODE_DIGITS) {
            return false;
        }
        long currentTime = System.currentTimeMillis() / 1000;
        long currentStep = currentTime / TIME_STEP_SECONDS;

        // Check current, previous, and next time step (±30s window)
        for (int i = -1; i <= 1; i++) {
            String expectedCode = generateCode(secret, currentStep + i);
            if (expectedCode.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the otpauth:// URI for QR code generation.
     * Format: otpauth://totp/Label?secret=SECRET&issuer=ISSUER&digits=6&period=30
     *
     * @param secret    Base64-encoded secret
     * @param account   user account identifier (e.g., email)
     * @param issuer    application name
     * @return otpauth URI
     */
    public static String buildOtpAuthUri(String secret, String account, String issuer) {
        return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d",
                issuer, account,
                Base64.getEncoder().encodeToString(secret.getBytes()).replace("=", ""),
                issuer, CODE_DIGITS, TIME_STEP_SECONDS
        );
    }

    /**
     * Generates a TOTP code for the given secret and time step.
     * Implements RFC 4226 HOTP algorithm.
     */
    private static String generateCode(String secret, long timeStep) {
        try {
            byte[] key = Base64.getDecoder().decode(secret);
            byte[] timeBytes = ByteBuffer.allocate(8).putLong(timeStep).array();

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(timeBytes);

            // Dynamic truncation (RFC 4226 §5.3)
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TOTP code", e);
        }
    }
}
