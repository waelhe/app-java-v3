package com.marketplace.shared.security;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;

import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Access-token generator scoped only to the native passkey extension grant.
 *
 * <p>The standard Spring Security Authorization Server grants keep using the
 * framework's internally configured token generator, including its default
 * customizers. This adapter is deliberately not an {@code OAuth2TokenGenerator}
 * bean, so it does not cause Spring Security's default grant providers to back
 * off. The native grant is limited to this platform's self-contained JWT client
 * and uses the same JWK source and application claim customizer.</p>
 */
@Component
public final class NativePasskeyAccessTokenGenerator {

    private final JwtGenerator delegate;

    public NativePasskeyAccessTokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer) {
        JwtGenerator generator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        generator.setJwtCustomizer(jwtTokenCustomizer);
        this.delegate = generator;
    }

    public OAuth2Token generate(OAuth2TokenContext context) {
        return this.delegate.generate(context);
    }
}
