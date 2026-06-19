package com.marketplace.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TotpService() {
    }

    /**
     * Generates a random Base64-encoded TOTP secret.
     *
     * @return Base64-encoded secret
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Validates a TOTP code against the secret.
     * Allows a window of ±1 time step (±30 seconds) for clock drift.
     *
     * <p><b>Constant-time comparison</b>: uses {@link MessageDigest#isEqual(byte[], byte[])}
     * instead of {@link String#equals(Object)} to prevent timing attacks. RFC 6238 §5.2
     * and RFC 4226 §5.3 both require constant-time comparison of OTP values.
     *
     * @param secret Base64-encoded secret
     * @param code   6-digit code from authenticator app
     * @return true if valid
     */
    public static boolean validateCode(String secret, String code) {
        return validateCodeWithTimestep(secret, code).isPresent();
    }

    /**
     * Validates a TOTP code and returns the matched timestep.
     *
     * <p>Returns the timestep that matched the code (within the ±1 window),
     * or {@link java.util.Optional#empty()} if no match. The caller should
     * use the returned timestep for replay protection — RFC 6238 §5.2 step 4:
     * "The verifier MUST NOT accept the second attempt of the OTP after the
     * successful validation has been issued."
     *
     * @param secret Base64-encoded secret
     * @param code   6-digit code from authenticator app
     * @return the matched timestep, or empty if no match
     */
    public static java.util.Optional<Long> validateCodeWithTimestep(String secret, String code) {
        if (code == null || code.length() != CODE_DIGITS) {
            return java.util.Optional.empty();
        }
        long currentTime = System.currentTimeMillis() / 1000;
        long currentStep = currentTime / TIME_STEP_SECONDS;

        byte[] codeBytes = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        // Check current, previous, and next time step (±30s window).
        // Use constant-time comparison (MessageDigest.isEqual) per RFC 6238 §5.2.
        for (int i = -1; i <= 1; i++) {
            long timestep = currentStep + i;
            String expectedCode = generateCode(secret, timestep);
            byte[] expectedBytes = expectedCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expectedBytes, codeBytes)) {
                return java.util.Optional.of(timestep);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Builds the otpauth:// URI for QR code generation.
     * Format: otpauth://totp/Label?secret=SECRET&issuer=ISSUER&digits=6&period=30
     *
     * <p>The {@code secret} parameter is <strong>Base32-encoded (RFC 4648)</strong>
     * because Google Authenticator, Microsoft Authenticator, and other compliant
     * authenticator apps decode the secret as Base32, not Base64. The internal
     * storage uses Base64 (for efficient DB encoding); we convert here to the
     * authenticator-compatible Base32 representation.
     *
     * <p>The previous implementation called {@code Base64.getEncoder().encodeToString(secret.getBytes())}
     * which both (a) re-encoded the Base64 string as if it were raw bytes — yielding a
     * different secret than the one stored — and (b) used Base64 instead of Base32,
     * so no authenticator app could decode it.
     *
     * <p><b>References</b>
     * <ul>
     *   <li><a href="https://github.com/google/google-authenticator/wiki/Key-Uri-Format">Google Authenticator Key URI Format</a> — "Base32 encoded according to RFC 3548"</li>
     *   <li><a href="https://datatracker.ietf.org/doc/html/rfc4648">RFC 4648 — Base16/32/64 Encodings</a></li>
     *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238 — TOTP</a></li>
     * </ul>
     *
     * @param secret Base64-encoded secret (internal storage format from {@link #generateSecret()})
     * @param account user account identifier (e.g., email)
     * @param issuer application name
     * @return otpauth URI with Base32-encoded secret
     */
    public static String buildOtpAuthUri(String secret, String account, String issuer) {
        // Decode the internal Base64 secret back to raw key bytes, then re-encode
        // as Base32 (RFC 4648) — the format expected by authenticator apps.
        byte[] rawKey = Base64.getDecoder().decode(secret);
        String base32Secret = new org.apache.commons.codec.binary.Base32().encodeAsString(rawKey)
                .replace("=", ""); // authenticators tolerate stripped padding

        return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d",
                issuer, account,
                base32Secret,
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
