package com.marketplace.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Type-safe, externally managed settings for native Android passkeys.
 *
 * <p>Leave the relying-party ID and origin list unset until the actual Android
 * signing identity is available. Native authentication then remains closed by
 * default instead of trusting a guessed development certificate.</p>
 */
@ConfigurationProperties("marketplace.security.passkeys")
public record NativePasskeyProperties(
        @DefaultValue("") String rpId,
        @DefaultValue("Marketplace") String rpName,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("com.marketplace.android") String androidPackageName,
        @DefaultValue List<String> androidSha256CertFingerprints) {

    public NativePasskeyProperties {
        rpId = rpId == null ? "" : rpId.trim();
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
                        .distinct()
                        .toList();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(rpId)
                && !allowedOrigins.isEmpty()
                && !androidSha256CertFingerprints.isEmpty();
    }
}
