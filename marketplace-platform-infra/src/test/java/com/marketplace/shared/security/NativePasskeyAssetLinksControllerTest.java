package com.marketplace.shared.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePasskeyAssetLinksControllerTest {

    private static final String FINGERPRINT = "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
            + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F";

    @Test
    void answers404UntilTheReleaseSigningIdentityIsConfigured() {
        NativePasskeyAssetLinksController controller = new NativePasskeyAssetLinksController(
                new NativePasskeyProperties("", "Marketplace", List.of(),
                        "com.marketplace.android", List.of()));

        assertThatThrownBy(controller::assetLinks)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void publishesTheDigitalAssetLinksStatementForTheConfiguredIdentity() {
        byte[] digest = java.util.HexFormat.of().parseHex(FINGERPRINT.replace(":", ""));
        String origin = "android:apk-key-hash:"
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        NativePasskeyAssetLinksController controller = new NativePasskeyAssetLinksController(
                new NativePasskeyProperties("example.com", "Marketplace", List.of(origin),
                        "com.marketplace.android", List.of(FINGERPRINT)));

        List<NativePasskeyAssetLinksController.AssetLinkStatement> statements = controller.assetLinks();

        assertThat(statements).hasSize(1);
        NativePasskeyAssetLinksController.AssetLinkStatement statement = statements.get(0);
        assertThat(statement.relation())
                .containsExactly(
                        "delegate_permission/common.handle_all_urls",
                        "delegate_permission/common.get_login_creds");
        assertThat(statement.target().namespace()).isEqualTo("android_app");
        assertThat(statement.target().package_name()).isEqualTo("com.marketplace.dayf");
        assertThat(statement.target().sha256_cert_fingerprints())
                .containsExactly(FINGERPRINT);
    }
}
