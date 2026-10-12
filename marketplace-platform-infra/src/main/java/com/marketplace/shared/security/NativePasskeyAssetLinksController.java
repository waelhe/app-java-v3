package com.marketplace.shared.security;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishes the Digital Asset Links statement required by Android Credential
 * Manager. It intentionally returns 404 until the real app-signing certificate
 * fingerprint is configured; a debug certificate is never trusted by default.
 */
@RestController
public final class NativePasskeyAssetLinksController {

    private final NativePasskeyProperties properties;

    public NativePasskeyAssetLinksController(NativePasskeyProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/assetlinks.json")
    public List<AssetLinkStatement> assetLinks() {
        if (this.properties.androidSha256CertFingerprints().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return List.of(new AssetLinkStatement(
                List.of("delegate_permission/common.get_login_creds"),
                new AssetLinkTarget(
                        "android_app",
                        this.properties.androidPackageName(),
                        this.properties.androidSha256CertFingerprints())));
    }

    public record AssetLinkStatement(List<String> relation, AssetLinkTarget target) {
    }

    public record AssetLinkTarget(String namespace, String package_name, List<String> sha256_cert_fingerprints) {
    }
}
