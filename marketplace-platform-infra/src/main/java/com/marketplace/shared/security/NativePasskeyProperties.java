package com.marketplace.shared.security;

import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Externally managed settings for native Android passkeys.
 *
 * <p>The relying-party ID, Android WebAuthn origin, and Digital Asset Links
 * certificate fingerprints must describe the same release-signing identity.
 * Invalid or partially configured values keep the feature unavailable.</p>
 */
@ConfigurationProperties("marketplace.security.passkeys")
public record NativePasskeyProperties(
        @DefaultValue("") String rpId,
        @DefaultValue("Marketplace") String rpName,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("com.marketplace.android") String androidPackageName,
        @DefaultValue List<String> androidSha256CertFingerprints) {

    private static final String ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:";
    private static final String SHA_256_HEX = "(?i)[0-9a-f]{64}";
    private static final String PACKAGE_NAME = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+";

    public NativePasskeyProperties {
        rpId = rpId == null ? "" : rpId.trim().toLowerCase(Locale.ROOT);
        rpName = StringUtils.hasText(rpName) ? rpName.trim() : "Marketplace";
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        androidPackageName = StringUtils.hasText(androidPackageName)
                ? androidPackageName.trim() : "com.marketplace.android";
        androidSha256CertFingerprints = androidSha256CertFingerprints == null
                ? List.of()
                : androidSha256CertFingerprints.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .map(NativePasskeyProperties::normalizeFingerprint)
                        .distinct()
                        .toList();
    }

    /**
     * True only when the allowlist exactly matches the origins derived from the
     * configured release-signing certificate fingerprints. This prevents a
     * mismatched Digital Asset Links association and WebAuthn origin allowlist.
     */
    public boolean isConfigured() {
        if (!StringUtils.hasText(rpId)
                || rpId.contains("://")
                || rpId.contains("/")
                || rpId.contains(":")
                || rpId.chars().anyMatch(Character::isWhitespace)
                || !androidPackageName.matches(PACKAGE_NAME)
                || allowedOrigins.isEmpty()
                || androidSha256CertFingerprints.isEmpty()) {
            return false;
        }

        Set<String> expectedOrigins = new HashSet<>();
        try {
            for (String fingerprint : androidSha256CertFingerprints) {
                String hex = fingerprint.replace(":", "");
                if (!hex.matches(SHA_256_HEX)) {
                    return false;
                }
                byte[] digest = HexFormat.of().parseHex(hex);
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
                expectedOrigins.add(ANDROID_ORIGIN_PREFIX + encoded);
            }
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return expectedOrigins.size() == androidSha256CertFingerprints.size()
                && expectedOrigins.equals(Set.copyOf(allowedOrigins));
    }

    private static String normalizeFingerprint(String value) {
        String hex = value.replace(":", "");
        if (!hex.matches(SHA_256_HEX)) {
            // Preserve invalid input so validation fails closed; do not silently
            // transform a malformed or incomplete operator value into a trusted one.
            return value;
        }
        String upperHex = hex.toUpperCase(Locale.ROOT);
        StringBuilder fingerprint = new StringBuilder(95);
        for (int i = 0; i < upperHex.length(); i += 2) {
            if (i > 0) {
                fingerprint.append(':');
            }
            fingerprint.append(upperHex, i, i + 2);
        }
        return fingerprint.toString();
    }
}
