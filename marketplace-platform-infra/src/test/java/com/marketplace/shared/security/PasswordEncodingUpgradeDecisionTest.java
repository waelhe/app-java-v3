package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.StandardPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-07 (official-compliance plan §6 wave A — A.6, «اختبار ترميز»): the
 * decision matrix of the {@link PasswordEncoder} the platform already
 * installs ({@code PasswordEncoderFactories.createDelegatingPasswordEncoder()}
 * — the reference's own default construction, Security › Features ›
 * Authentication › Password Storage).
 *
 * <p><b>Why guard the framework's own semantics.</b> The login-time upgrade
 * wired in {@code SecurityConfig} delegates every decision to
 * {@code PasswordEncoder#upgradeEncoding(String)} — Spring Security 7.x has
 * no provider-level switch anymore (the 6.x {@code setUpgradeEncoding} was
 * removed; verified against the 7.1.1 bytecode this change). This slice pins
 * the contract the wiring relies on, so a framework bump that changes the
 * decision semantics fails HERE — at the guard — instead of silently
 * changing which stored verifiers get rewritten at login.
 *
 * <p><b>The matrix, each leg measured:</b></p>
 * <ul>
 *   <li>a stored form carrying a foreign {@code {id}} (the doc's own
 *       examples: {@code {sha256}}, and the getting-started {@code {noop}})
 *       upgrades — the reference's stated purpose: «we can match on any
 *       password encoding but encode passwords by using the most modern
 *       password encoding»;</li>
 *   <li>a stored {@code {bcrypt}} hash at the configured strength does NOT
 *       upgrade — a healthy verifier must never be rewritten (no churn, no
 *       gratuitous history);</li>
 *   <li>a stored {@code {bcrypt}} hash at a WEAKER strength upgrades —
 *       BCrypt's own {@code upgradeEncoding} compares the embedded cost
 *       against the configured one;</li>
 *   <li>matching stays id-driven across encodings — the legacy verifier
 *       still authenticates the same raw password (the premise the upgrade
 *       path builds on: match first, upgrade only on success).</li>
 * </ul>
 */
class PasswordEncodingUpgradeDecisionTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private static final String RAW = "it-a07-raw-password";

    /** The doc's {sha256} row shape: StandardPasswordEncoder under the id. */
    private static String sha256Form() {
        return "{sha256}" + new StandardPasswordEncoder("").encode(RAW);
    }

    /** A bcrypt row at the default strength — the healthy non-upgrade case. */
    private static String healthyBcryptForm() {
        return ENCODER.encode(RAW);
    }

    /** A bcrypt row at a deliberately weaker cost (4 < the default 10). */
    private static String weakBcryptForm() {
        return "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(RAW);
    }

    @Test
    void foreignIdUpgrades() {
        assertThat(ENCODER.upgradeEncoding(sha256Form()))
                .as("a {sha256} stored form must upgrade to the preferred {bcrypt}")
                .isTrue();
        assertThat(ENCODER.upgradeEncoding("{noop}" + RAW))
                .as("the getting-started {noop} stored form must upgrade")
                .isTrue();
    }

    @Test
    void healthyBcryptDoesNotUpgrade() {
        assertThat(ENCODER.upgradeEncoding(healthyBcryptForm()))
                .as("a {bcrypt} verifier at the configured strength must NOT be rewritten")
                .isFalse();
    }

    @Test
    void weakerBcryptStrengthUpgrades() {
        assertThat(ENCODER.upgradeEncoding(weakBcryptForm()))
                .as("a {bcrypt} verifier below the configured strength must upgrade")
                .isTrue();
    }

    @Test
    void legacyVerifierStillMatchesTheRawPassword() {
        assertThat(ENCODER.matches(RAW, sha256Form()))
                .as("the id-driven match must authenticate the legacy verifier (upgrade happens only after success)")
                .isTrue();
        assertThat(ENCODER.matches(RAW, weakBcryptForm()))
                .as("the id-driven match must authenticate the weak-strength verifier")
                .isTrue();
        assertThat(ENCODER.matches("not-" + RAW, sha256Form()))
                .as("a wrong raw password must fail against the legacy verifier")
                .isFalse();
    }

    @Test
    void reEncodingProducesThePreferredForm() {
        String reEncoded = ENCODER.encode(RAW);

        assertThat(reEncoded)
                .as("the delegating encoder always encodes with the preferred id")
                .startsWith("{bcrypt}");
        assertThat(ENCODER.upgradeEncoding(reEncoded))
                .as("the freshly encoded form must itself be a terminal form (no upgrade loop)")
                .isFalse();
    }
}
