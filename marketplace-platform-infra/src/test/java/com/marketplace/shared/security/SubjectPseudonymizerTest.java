package com.marketplace.shared.security;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * I7 (account-pseudonymization-plan §2 gate b-2(b) — the exact contract the
 * plan pins for the transformation): the derivation is the FULL 256-bit
 * HMAC-SHA256 fingerprint in hex (no truncation, no normalization), it is
 * deterministic under the same key (idempotence + the tombstone probe's
 * verifiability), it separates keys and subjects, and an unbound secret
 * channel keeps the capability OFF (503 SU-001), never a silent fallback.
 *
 * <p>The first test recomputes the expected value with the JDK's own
 * {@code javax.crypto} + {@code HexFormat} independently of the production
 * code path — the byte-level truth, not a self-confirming echo.
 */
class SubjectPseudonymizerTest {

    private static final String KEY = "it-pseudonymization-key";
    private static final String OTHER_KEY = "a-different-channel-key";
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    private SubjectPseudonymizer pseudonymizer(String key) {
        MarketplaceProperties properties = new MarketplaceProperties(
                null,
                new MarketplaceProperties.Security(
                        null, null, null,
                        new MarketplaceProperties.Security.Pseudonymization(key)));
        return new SubjectPseudonymizer(properties);
    }

    @Test
    void derive_matchesIndependentJdkHmacComputation() {
        String subject = "auth0|legacy-subject-42";
        SubjectPseudonymizer pseudonymizer = pseudonymizer(KEY);

        String derived = pseudonymizer.derive(subject);

        // Independent computation: javax.crypto + HexFormat, byte for byte.
        byte[] expected = hmacSha256(KEY, subject);
        assertThat(derived).isEqualTo(SubjectPseudonymizer.ANON_PREFIX + HexFormat.of().formatHex(expected));
    }

    @Test
    void derive_isDeterministic_sameKeySameSubject() {
        SubjectPseudonymizer pseudonymizer = pseudonymizer(KEY);

        assertThat(pseudonymizer.derive("sub-1")).isEqualTo(pseudonymizer.derive("sub-1"));
    }

    @Test
    void derive_formatIsFullFingerprintHexUnderTheColumnLimit() {
        String derived = pseudonymizer(KEY).derive("sub-1");

        // "anon-" (5, hyphen included) + the full 64-char hex fingerprint
        // = 69 characters — within the 200-char users.subject column limit.
        // (The plan's §2 b-2(b) arithmetic "5+1+64=70" double-counts the
        // hyphen; the measured truth is 69 — corrected in the plan doc in
        // the same PR.)
        assertThat(derived).startsWith("anon-");
        String fingerprint = derived.substring("anon-".length());
        assertThat(fingerprint).hasSize(64);
        assertThat(fingerprint).matches(HEX_64);
        assertThat(derived).hasSize(69);
    }

    @Test
    void derive_separatesKeys_andSeparatesSubjects() {
        SubjectPseudonymizer withKey = pseudonymizer(KEY);
        SubjectPseudonymizer withOtherKey = pseudonymizer(OTHER_KEY);

        assertThat(withKey.derive("sub-1")).isNotEqualTo(withOtherKey.derive("sub-1"));
        assertThat(withKey.derive("sub-1")).isNotEqualTo(withKey.derive("sub-2"));
    }

    @Test
    void derive_treatsTheInputAsOpaqueBytes_noNormalization() {
        // Subjects are opaque identifiers matched literally — case and shape
        // must survive to the HMAC input unchanged, so distinct spellings
        // derive distinct replacements (and the identical spelling derives
        // the identical replacement).
        SubjectPseudonymizer pseudonymizer = pseudonymizer(KEY);

        assertThat(pseudonymizer.derive("Sub-1")).isNotEqualTo(pseudonymizer.derive("sub-1"));
        assertThat(pseudonymizer.derive(" sub-1")).isNotEqualTo(pseudonymizer.derive("sub-1"));

        // Non-ASCII (the byte channel is UTF-8, as-is).
        String arabic = "مستخدم-١٢٣";
        assertThat(pseudonymizer.derive(arabic))
                .isEqualTo(SubjectPseudonymizer.ANON_PREFIX
                        + HexFormat.of().formatHex(hmacSha256(KEY, arabic)));
    }

    @Test
    void unboundChannel_isOffNotBroken() {
        SubjectPseudonymizer blank = pseudonymizer("");

        assertThat(blank.isConfigured()).isFalse();
        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> blank.derive("sub-1"));
        assertThat(ex.getMessage()).contains("PSEUDONYMIZATION_HMAC_KEY");
    }

    @Test
    void boundChannel_reportsConfigured() {
        assertThat(pseudonymizer(KEY).isConfigured()).isTrue();
    }

    private static byte[] hmacSha256(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
