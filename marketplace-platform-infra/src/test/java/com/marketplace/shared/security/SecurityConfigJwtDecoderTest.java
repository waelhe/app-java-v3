package com.marketplace.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.shared.config.MarketplaceProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigJwtDecoderTest {

    private static final String ISSUER = "https://auth.marketplace.test";
    private static final String AUDIENCE = "marketplace-api";

    private RSAKey rsaKey;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = generateRsaKey();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        jwtDecoder = new SecurityConfig(properties(), new ObjectMapper(), resourceServerProperties(null, List.of())).jwtDecoder(jwkSource);
    }

    @Test
    void rejectsSignedJwtWithWrongIssuer() throws Exception {
        String token = signedJwt("https://wrong-issuer.test", AUDIENCE);

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void rejectsSignedJwtWithWrongAudience() throws Exception {
        String token = signedJwt(ISSUER, "wrong-audience");

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }


    @Test
    void appliesSpringBootResourceServerIssuerAudienceAndPublicKeySettings() throws Exception {
        OAuth2ResourceServerProperties resourceServerProperties = resourceServerProperties(
                "https://boot-issuer.marketplace.test",
                List.of("marketplace-api", "mobile-api")
        );
        resourceServerProperties.getJwt().setPublicKeyLocation(publicKeyResource());
        jwtDecoder = new SecurityConfig(properties(), new ObjectMapper(), resourceServerProperties).jwtDecoder(
                new ImmutableJWKSet<>(new JWKSet(generateRsaKey())));
        String token = signedJwt("https://boot-issuer.marketplace.test", List.of("marketplace-api", "mobile-api"));

        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getIssuer()).hasToString("https://boot-issuer.marketplace.test");
        assertThat(jwt.getAudience()).containsExactly("marketplace-api", "mobile-api");
    }

    @Test
    void acceptsTokenWithAnyConfiguredSpringBootResourceServerAudience() throws Exception {
        OAuth2ResourceServerProperties resourceServerProperties = resourceServerProperties(
                ISSUER,
                List.of("marketplace-api", "mobile-api")
        );
        resourceServerProperties.getJwt().setPublicKeyLocation(publicKeyResource());
        jwtDecoder = new SecurityConfig(properties(), new ObjectMapper(), resourceServerProperties).jwtDecoder(
                new ImmutableJWKSet<>(new JWKSet(generateRsaKey())));
        String token = signedJwt(ISSUER, "marketplace-api");

        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getAudience()).containsExactly("marketplace-api");
    }

    @Test
    void acceptsSignedJwtWithExpectedIssuerAndAudience() throws Exception {
        String token = signedJwt(ISSUER, AUDIENCE);

        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getIssuer()).hasToString(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
    }

    private String signedJwt(String issuer, String audience) throws Exception {
        return signedJwt(issuer, List.of(audience));
    }

    private String signedJwt(String issuer, List<String> audiences) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-123")
                .audience(audiences)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(rsaKey.getKeyID())
                        .build(),
                claims
        );
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return jwt.serialize();
    }

    private ByteArrayResource publicKeyResource() throws Exception {
        String publicKey = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                rsaKey.toRSAPublicKey().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----\n";
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
    }

    private static RSAKey generateRsaKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
    }

    private static OAuth2ResourceServerProperties resourceServerProperties(String issuer, List<String> audiences) {
        OAuth2ResourceServerProperties resourceServerProperties = new OAuth2ResourceServerProperties();
        resourceServerProperties.getJwt().setIssuerUri(issuer);
        resourceServerProperties.getJwt().setAudiences(audiences);
        return resourceServerProperties;
    }

    private static MarketplaceProperties properties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                                AUDIENCE
                        ),
                        new MarketplaceProperties.Security.AuthServer(ISSUER)
                )
        );
    }
}
