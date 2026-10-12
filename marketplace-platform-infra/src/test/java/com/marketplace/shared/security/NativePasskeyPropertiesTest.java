package com.marketplace.shared.security;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativePasskeyPropertiesTest {

    private static final String FINGERPRINT = "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
            + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F";

    @Test
    void enablesOnlyWhenAndroidOriginMatchesDigitalAssetLinksFingerprint() {
        String origin = originFrom(FINGERPRINT);
        NativePasskeyProperties properties = properties("example.com", List.of(origin), List.of(FINGERPRINT));

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.androidSha256CertFingerprints()).containsExactly(
                "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
                        + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F");
    }

    @Test
    void remainsDisabledWhenOriginAndCertificateFingerprintDoNotMatch() {
        String correctOrigin = originFrom(FINGERPRINT);
        String wrongOrigin = correctOrigin.substring(0, correctOrigin.length() - 1)
                + (correctOrigin.endsWith("A") ? "B" : "A");

        assertThat(properties("example.com", List.of(wrongOrigin), List.of(FINGERPRINT)).isConfigured())
                .isFalse();
    }

    @Test
    void remainsDisabledForMalformedFingerprint() {
        assertThat(properties("example.com", List.of("android:apk-key-hash:invalid"), List.of("not-a-fingerprint"))
                .isConfigured()).isFalse();
    }

    @Test
    void remainsDisabledForUrlInsteadOfRelyingPartyId() {
        String origin = originFrom(FINGERPRINT);
        assertThat(properties("https://example.com/path", List.of(origin), List.of(FINGERPRINT)).isConfigured())
                .isFalse();
    }

    private static NativePasskeyProperties properties(
            String rpId, List<String> origins, List<String> fingerprints) {
        return new NativePasskeyProperties(
                rpId, "Marketplace", origins, "com.marketplace.android", fingerprints);
    }

    private static String originFrom(String fingerprint) {
        byte[] digest = HexFormat.of().parseHex(fingerprint.replace(":", ""));
        return "android:apk-key-hash:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
