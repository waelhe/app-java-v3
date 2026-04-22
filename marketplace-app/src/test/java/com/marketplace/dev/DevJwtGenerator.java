package com.marketplace.dev;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Standalone utility to generate JWTs signed with the dev-only RSA private key.
 * <p>
 * NOT part of the application — run from IDE or {@code mvn exec:java} to obtain
 * a Bearer Token for local development against the Resource Server.
 * <p>
 * The corresponding public key is at {@code resources/keys/dev-rsa.pub} and
 * Spring Boot reads it via {@code public-key-location} in {@code application-dev.yml}.
 */
public final class DevJwtGenerator {

    private static final String PRIVATE_KEY_RESOURCE = "/keys/dev-rsa-private.pem";
    private static final long EXPIRY_MINUTES = 60;

    private DevJwtGenerator() {}

    public static void main(String[] args) throws Exception {
        RSAPrivateKey privateKey = loadPrivateKey();

        String subject = args.length > 0 ? args[0] : "dev-user";
        List<String> roles = args.length > 1
                ? List.of(args[1].split(","))
                : List.of("USER");

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .issuer("marketplace-dev")
                .audience("marketplace-api")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + EXPIRY_MINUTES * 60_000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader(JWSAlgorithm.RS256),
                claims
        );
        signedJWT.sign(new RSASSASigner(privateKey));

        String token = signedJWT.serialize();
        System.out.println("=== Dev JWT (expires in " + EXPIRY_MINUTES + " min) ===");
        System.out.println("Subject: " + subject);
        System.out.println("Roles:   " + roles);
        System.out.println();
        System.out.println(token);
    }

    private static RSAPrivateKey loadPrivateKey() throws Exception {
        try (InputStream is = DevJwtGenerator.class.getResourceAsStream(PRIVATE_KEY_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Dev private key not found: " + PRIVATE_KEY_RESOURCE);
            }
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getMimeDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
    }
}
