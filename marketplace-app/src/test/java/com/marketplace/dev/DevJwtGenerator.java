package com.marketplace.dev;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Standalone utility that mints a local-development Bearer token with the SAME
 * signing key the running application's authorization server loads — the
 * keystore channel documented in {@code marketplace-app/src/main/resources/keys/README.md}
 * and bound by {@code application-dev.yml}: {@code JWT_KEYSTORE_B64}/
 * {@code JWT_KEYSTORE_PATH} plus {@code JWT_KEYSTORE_PASSWORD},
 * {@code JWT_KEY_ALIAS} and {@code JWT_KEY_PASSWORD}.
 *
 * <p>Resolution mirrors {@code SecurityConfig#jwkSource}: base64 bytes win over
 * the file path, a configured source with missing credentials is an error
 * (never a silent fallback), and the token's {@code kid} is the keystore alias —
 * exactly like the authorization server's JWK ({@code .keyID(keyAlias)}). The
 * resource server's decoder validates the signature against that same JWK set
 * and enforces the authorization server's issuer
 * ({@code JwtValidators.createDefaultWithIssuer}), so this utility stamps the
 * same issuer ({@code AUTH_SERVER_ISSUER}, default {@code http://localhost:8080})
 * and the resource server's audience ({@code JWT_AUDIENCE}, default
 * {@code marketplace-api}). The previous revision's {@code public-key-location}
 * design no longer exists anywhere in the tree — this is the complete move to
 * the architecture the auth-server redesign shipped.
 *
 * <p>NOT part of the application — run from IDE or {@code mvn exec:java} against
 * a locally running instance that loaded the same keystore. When the app runs
 * with an ephemeral signing key (both keystore channels blank — the Spring
 * Authorization Server quickstart mode, allowed in dev/test only per
 * auth-system-redesign-plan INV-7), no offline tool can match it: that key
 * lives only inside the running JVM, so this utility prints the setup guidance
 * and exits instead of minting a token that would fail validation.
 *
 * <p>No key material is committed anywhere: the keystore is generated locally
 * with {@code keytool} and stays outside the repository (policy:
 * {@code docs/security/secrets-policy.md} — no committed credentials, ever).
 */
public final class DevJwtGenerator {

    private static final String DEFAULT_KEYSTORE_PATH = "./marketplace-app/local/dev-authserver.jks";
    private static final String DEFAULT_KEY_ALIAS = "marketplace-auth";
    private static final String DEFAULT_ISSUER = "http://localhost:8080";
    private static final String DEFAULT_AUDIENCE = "marketplace-api";
    private static final long EXPIRY_MINUTES = 60;

    private DevJwtGenerator() {}

    public static void main(String[] args) throws Exception {
        String subject = args.length > 0 ? args[0] : "dev-user";
        List<String> roles = args.length > 1
                ? List.of(args[1].split(","))
                : List.of("USER");

        KeystoreChannel channel = KeystoreChannel.fromEnvironment();
        if (!channel.hasSource()) {
            exitWithGuidance("No keystore source is configured (JWT_KEYSTORE_B64 and JWT_KEYSTORE_PATH"
                    + " are both blank). The running app would sign with an ephemeral in-memory key"
                    + " in dev/test, and a token minted offline can never match it. Generate the local"
                    + " development keystore and export the channel —"
                    + " marketplace-app/src/main/resources/keys/README.md has the exact commands.");
        }
        if (!channel.complete()) {
            exitWithGuidance("A keystore source is configured but its credentials are incomplete"
                    + " (JWT_KEYSTORE_PASSWORD, JWT_KEY_ALIAS and JWT_KEY_PASSWORD must all be set)."
                    + " A half-configured keystore is an error, exactly like SecurityConfig#jwkSource.");
        }

        RSAPrivateKey privateKey = loadPrivateKey(channel);
        String issuer = envOr("AUTH_SERVER_ISSUER", DEFAULT_ISSUER);
        String audience = envOr("JWT_AUDIENCE", DEFAULT_AUDIENCE);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .issuer(issuer)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + EXPIRY_MINUTES * 60_000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(channel.alias)
                        .build(),
                claims
        );
        signedJWT.sign(new RSASSASigner(privateKey));

        System.out.println("=== Dev JWT (expires in " + EXPIRY_MINUTES + " min) ===");
        System.out.println("Subject:  " + subject);
        System.out.println("Roles:    " + roles);
        System.out.println("Issuer:   " + issuer + "  (the app's auth-server issuer)");
        System.out.println("Audience: " + audience + "  (the resource server's audience)");
        System.out.println("kid:      " + channel.alias + "  (the keystore alias, like the app's JWK)");
        System.out.println();
        System.out.println(signedJWT.serialize());
    }

    /**
     * The same keystore channel the application binds
     * ({@code marketplace.security.jwt.keystore.*} from {@code JWT_KEYSTORE_*}),
     * carrying the dev defaults of {@code application-dev.yml}. A variable that
     * is set but empty resolves to empty — the same placeholder semantics the
     * profile files use — so an explicitly blank source stays blank.
     */
    private record KeystoreChannel(String b64, String path, String password, String alias, String keyPassword) {

        static KeystoreChannel fromEnvironment() {
            return new KeystoreChannel(
                    envOr("JWT_KEYSTORE_B64", ""),
                    envOr("JWT_KEYSTORE_PATH", DEFAULT_KEYSTORE_PATH),
                    envOr("JWT_KEYSTORE_PASSWORD", ""),
                    envOr("JWT_KEY_ALIAS", DEFAULT_KEY_ALIAS),
                    envOr("JWT_KEY_PASSWORD", ""));
        }

        boolean hasSource() {
            return isNotBlank(b64) || isNotBlank(path);
        }

        boolean complete() {
            return isNotBlank(password) && isNotBlank(alias) && isNotBlank(keyPassword);
        }
    }

    /**
     * Loads the private signing key from the configured channel. Resolution
     * mirrors {@code SecurityConfig#jwkSource}: base64 bytes win over the file
     * path, and a bare path is a file relative to the working directory (the
     * {@code file:} prefix is accepted and stripped; {@code classpath:} has no
     * meaning for a local development keystore and is not consulted here).
     */
    private static RSAPrivateKey loadPrivateKey(KeystoreChannel channel) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        if (isNotBlank(channel.b64)) {
            byte[] keystoreBytes = Base64.getDecoder().decode(channel.b64.replaceAll("\\s", ""));
            keyStore.load(new ByteArrayInputStream(keystoreBytes), channel.password.toCharArray());
        } else {
            String location = channel.path.startsWith("file:")
                    ? channel.path.substring("file:".length())
                    : channel.path;
            try (InputStream inputStream = Files.newInputStream(Path.of(location))) {
                keyStore.load(inputStream, channel.password.toCharArray());
            }
        }
        Key key = keyStore.getKey(channel.alias, channel.keyPassword.toCharArray());
        if (key == null) {
            throw new IllegalStateException("No private key found in keystore for alias " + channel.alias);
        }
        return (RSAPrivateKey) key;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void exitWithGuidance(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
