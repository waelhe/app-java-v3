package com.marketplace.shared.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

/**
 * Publishes the Digital Asset Links statement required by Android Credential
 * Manager. It intentionally returns 404 until the real app-signing certificate
 * fingerprint is configured; a debug certificate must not be trusted by default.
 */
@RestController
public final class NativePasskeyAssetLinksController {

    private final String packageName;
    private final List<String> fingerprints;

    public NativePasskeyAssetLinksController(
            @Value("\${ANDROID_APP_PACKAGE_NAME:com.marketplace.android}") String packageName,
            @Value("\${ANDROID_APP_SHA256_CERT_FINGERPRINTS:}") String fingerprints) {
        this.packageName = packageName.trim();
        this.fingerprints = StringUtils.hasText(fingerprints)
                ? Arrays.stream(fingerprints.split(",")).map(String::trim)
                    .filter(StringUtils::hasText).toList()
                : List.of();
    }

    @GetMapping("/.well-known/assetlinks.json")
    public List<AssetLinkStatement> assetLinks() {
        if (this.fingerprints.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return List.of(new AssetLinkStatement(
                List.of("delegate_permission/common.get_login_creds"),
                new AssetLinkTarget("android_app", this.packageName, this.fingerprints)));
    }

    public record AssetLinkStatement(List<String> relation, AssetLinkTarget target) {
    }

    public record AssetLinkTarget(String namespace, String package_name, List<String> sha256_cert_fingerprints) {
    }
}
