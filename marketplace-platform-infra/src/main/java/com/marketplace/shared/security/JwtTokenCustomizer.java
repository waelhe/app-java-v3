package com.marketplace.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.UUID;

/**
 * Customizes JWT access tokens issued by Spring Authorization Server to include
 * a {@code jti} (JWT ID) claim, enabling per-token revocation via the
 * {@code /oauth2/revoke} endpoint (RFC 7009).
 *
 * <p>Without {@code jti}, token revocation is a no-op — the revocation endpoint
 * has no unique identifier to match against. This was identified as a critical
 * security gap in the auth chain audit.
 *
 * <p><b>Reference</b>
 * <ul>
 *   <li><a href="https://docs.spring.io/spring-authorization-server/reference/guides/how-to-custom-claims-authorities.html">
 *       Spring Authorization Server — How-to: Customize JWT Claims</a>:
 *       "You may add your own custom claims to an access token using an
 *        OAuth2TokenCustomizer&lt;JwtEncodingContext&gt; @Bean."</li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc9068#section-2.2">
 *       RFC 9068 §2.2</a>: "The 'jti' (JWT ID) claim [...] provides a unique
 *       identifier for the JWT."</li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc7009">
 *       RFC 7009</a>: OAuth 2.0 Token Revocation.</li>
 * </ul>
 */
@Configuration
public class JwtTokenCustomizer {

    /**
     * Adds a random {@code jti} claim to every access token issued by the
     * authorization server. This enables the {@code /oauth2/revoke} endpoint
     * to revoke individual tokens by their {@code jti}.
     *
     * @return the token customizer bean
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().id(UUID.randomUUID().toString());
            }
        };
    }
}
