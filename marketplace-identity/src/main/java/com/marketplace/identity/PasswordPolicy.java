package com.marketplace.identity;

import java.nio.charset.StandardCharsets;

/**
 * The platform's single password-shape policy: at least 8 and at most 72
 * UTF-16 characters AND at most 72 UTF-8 bytes — bcrypt's documented input
 * ceiling (Spring Security's own {@code BCrypt} bound), so policy-valid
 * input is always hashable: never silently truncated by the encoder and
 * never an encoder-time 500 the caller cannot answer.
 *
 * <p>Provenance: the shape was first enforced correctly on the reset
 * redemption leg ({@code PasswordResetService}, the #757 review round
 * 2026-10-29) and is extracted here so EVERY password-accepting entry
 * point — REST registration, reset redemption, self-service change, and
 * the GraphQL account register mirror — shares the ONE bound. The
 * character count alone is NOT the ceiling: a 40-character Arabic
 * password is ~80 UTF-8 bytes and passed character-only checks while
 * exceeding the bcrypt limit (the shard review's Minor·Security finding,
 * 2026-11-01).</p>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * {@return true when the raw password satisfies the shared shape
     * policy — 8..72 UTF-16 characters and at most 72 UTF-8 bytes}.
     */
    public static boolean withinSharedPolicy(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            return false;
        }
        // The bcrypt byte ceiling (Spring Security's own bound): at most 72
        // UTF-8 bytes of password material reach the hasher.
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /**
     * The classified rejection for a policy-violating password — maps to
     * the VALIDATION taxonomy through the shared illegal-argument handler.
     */
    public static IllegalArgumentException violation() {
        return new IllegalArgumentException(
                "The password must be at least 8 characters and at most 72 bytes"
                        + " (the bcrypt byte ceiling)");
    }
}
