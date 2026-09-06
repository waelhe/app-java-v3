package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate test for the production signing-key policy (governing plan
 * {@code docs/security/auth-system-redesign-plan.md}, decision D6 / invariant
 * INV-7: "لا مفاتيح توقيع عابرة في غير بيئة التطوير" — no ephemeral signing
 * keys outside development; risk-register mitigation: "فحص CI يحظر
 * {@code JWT_KEYSTORE_*} الفارغة في الإنتاج").
 *
 * <p>This is the CI check mandated by the risk register. It pins the contract of
 * {@link SecurityConfig#jwkSource(org.springframework.core.io.ResourceLoader,
 * org.springframework.core.env.Environment)} on six branches:</p>
 *
 * <ol>
 *   <li><b>prod + blank keystore ⇒ fail-fast</b> — profile-gated exactly like
 *   {@code OAuth2ClientSecretInitializer} (never a global fail-fast that would
 *       break dev/CI). A silent fallback to an ephemeral key in production would
 *       invalidate every issued token on each restart and break multi-instance
 *       deployments.</li>
 *   <li><b>non-prod + blank keystore ⇒ ephemeral quickstart key</b> — the official
 *       Spring Authorization Server Getting Started pattern ("a minimal
 *       configuration for getting started quickly", keys generated on startup),
 *       deliberately allowed for dev/test only.</li>
 *   <li><b>prod + configured keystore ⇒ persistent JKS key</b> — verified here
 *       against a <em>real</em> keystore generated with {@code keytool} (RSA-2048,
 *       JKS), committed under {@code src/test/resources/keys/test-jwt.jks}; the
 *       loaded key must carry the configured alias as its {@code keyID} and a
 *       private part, proving the production path end-to-end at the bean level.</li>
 *   <li><b>prod + b64 channel ⇒ persistent JKS key, no file involved</b> — the
 *       write-only-variable channel ({@code JWT_KEYSTORE_B64}) the Railway
 *       production deployment uses: base64 JKS bytes decoded in memory by
 *       {@code KeyStore.load(InputStream, char[])}.</li>
 *   <li><b>b64 without complete credentials ⇒ fail-fast in every profile</b> — a
 *       half-configured keystore is a misconfiguration, never an intentional
 *       quickstart; it must not silently degrade to an ephemeral key.</li>
 *   <li><b>b64 wins over path</b> — when both channels are present the base64
 *       channel is authoritative (the production reality: the path value points
 *       at a location only the legacy entrypoint ever materialized).</li>
 * </ol>
 *
 * <p>Unit level (no Spring context), mirroring {@code JwtRolesRoundTripTest}:
 * beans are pinned inside the module that declares them, while the wire-level
 * behavior is covered by {@code AuthorizationServerLoginGateIntegrationTest}.</p>
 */
class JwkSourceProdHardeningTest {

    private static final String TEST_KEYSTORE = "classpath:keys/test-jwt.jks";
    private static final String STORE_PASSWORD = "test-store-pass";
    private static final String KEY_ALIAS = "test-jwt";
    private static final String KEY_PASSWORD = "test-key-pass";

    @Test
    void failsFastWhenKeystoreBlankInProdProfile() {
        SecurityConfig config = new SecurityConfig(properties("", "", "", "", ""), null);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.jwkSource(new DefaultResourceLoader(), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace.security.jwt.keystore.b64")
                .hasMessageContaining("ephemeral signing keys are forbidden outside development");
    }

    @Test
    void generatesEphemeralQuickstartKeyOutsideProdWhenKeystoreBlank() throws Exception {
        SecurityConfig config = new SecurityConfig(properties("", "", "", "", ""), null);
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        JWKSource<SecurityContext> source = config.jwkSource(new DefaultResourceLoader(), dev);

        assertThat(source).isInstanceOf(ImmutableJWKSet.class);
        JWKSet jwkSet = ((ImmutableJWKSet<SecurityContext>) source).getJWKSet();
        assertThat(jwkSet.getKeys()).hasSize(1);
        RSAKey key = (RSAKey) jwkSet.getKeys().get(0);
        assertThat(key.isPrivate()).isTrue();
        // quickstart pattern: random keyID (UUID) — never stable identity
        assertThat(key.getKeyID()).isNotBlank();
    }

    @Test
    void loadsPersistentKeystoreWhenConfiguredInProdProfile() throws Exception {
        SecurityConfig config = new SecurityConfig(
                properties(TEST_KEYSTORE, "", STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD), null);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        JWKSource<SecurityContext> source = config.jwkSource(new DefaultResourceLoader(), prod);

        assertThat(source).isInstanceOf(ImmutableJWKSet.class);
        JWKSet jwkSet = ((ImmutableJWKSet<SecurityContext>) source).getJWKSet();
        assertThat(jwkSet.getKeys()).hasSize(1);
        RSAKey key = (RSAKey) jwkSet.getKeys().get(0);
        assertThat(key.isPrivate()).isTrue();
        // production identity: the configured alias is the keyID
        assertThat(key.getKeyID()).isEqualTo(KEY_ALIAS);
        assertThat(key.size()).isEqualTo(2048);
    }

    @Test
    void loadsPersistentKeystoreFromB64ChannelInProdProfile() throws Exception {
        // The write-only-variable channel (JWT_KEYSTORE_B64) used by the Railway
        // production deployment: base64 of the same real keytool JKS, decoded in
        // memory — no file is ever touched.
        SecurityConfig config = new SecurityConfig(
                properties("", testKeystoreB64(), STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD), null);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        JWKSource<SecurityContext> source = config.jwkSource(new DefaultResourceLoader(), prod);

        assertThat(source).isInstanceOf(ImmutableJWKSet.class);
        JWKSet jwkSet = ((ImmutableJWKSet<SecurityContext>) source).getJWKSet();
        assertThat(jwkSet.getKeys()).hasSize(1);
        RSAKey key = (RSAKey) jwkSet.getKeys().get(0);
        assertThat(key.isPrivate()).isTrue();
        // production identity preserved through the b64 channel: alias is the keyID
        assertThat(key.getKeyID()).isEqualTo(KEY_ALIAS);
        assertThat(key.size()).isEqualTo(2048);
    }

    @Test
    void failsFastWhenB64ConfiguredWithoutCredentialsEvenOutsideProd() throws Exception {
        // A half-configured keystore is a misconfiguration in ANY profile — it must
        // never silently degrade to an ephemeral quickstart key.
        SecurityConfig config = new SecurityConfig(
                properties("", testKeystoreB64(), "", KEY_ALIAS, KEY_PASSWORD), null);
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThatThrownBy(() -> config.jwkSource(new DefaultResourceLoader(), dev))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password/.alias/.keyPassword are required")
                .hasMessageContaining("b64")
                .hasMessageContaining("never falls back to an ephemeral key");
    }

    @Test
    void b64ChannelTakesPrecedenceOverPathChannel() throws Exception {
        // Both channels present: the path points at a location that has never existed
        // on the platform (the legacy entrypoint materialization target). b64 must
        // win — otherwise resource loading would fail with FileNotFoundException.
        SecurityConfig config = new SecurityConfig(
                properties("classpath:keys/never-materialized.jks", testKeystoreB64(),
                        STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD), null);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        JWKSource<SecurityContext> source = config.jwkSource(new DefaultResourceLoader(), prod);

        JWKSet jwkSet = ((ImmutableJWKSet<SecurityContext>) source).getJWKSet();
        RSAKey key = (RSAKey) jwkSet.getKeys().get(0);
        assertThat(key.getKeyID()).isEqualTo(KEY_ALIAS);
        assertThat(key.isPrivate()).isTrue();
    }

    /**
     * Base64 of the real committed test keystore ({@code keys/test-jwt.jks}) — the
     * exact shape the production {@code JWT_KEYSTORE_B64} variable carries.
     */
    private static String testKeystoreB64() throws Exception {
        try (var in = new ClassPathResource("keys/test-jwt.jks").getInputStream()) {
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }

    private static MarketplaceProperties properties(String path, String b64, String storePassword,
                                                     String alias, String keyPassword) {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore(path, b64, storePassword, alias, keyPassword),
                                "marketplace-api"
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))
                )
        );
    }
}
