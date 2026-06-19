package com.marketplace.shared.api;

import java.time.Instant;

/**
 * Port for revoking directly-issued JWTs (password login, social login).
 * Implemented by the infrastructure layer, consumed by the Identity module's
 * SessionController.
 *
 * <p>Direct-issued JWTs are NOT recorded in the oauth2_authorization table
 * (only Spring Authorization Server's /oauth2/token endpoint writes there).
 * This port provides a Redis-based revocation list checked by JwtRevocationValidator
 * on every authenticated request.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security JWT Resource Server</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7009">RFC 7009 -- Token Revocation</a>
 */
public interface JwtRevocationPort {

    /**
     * Revokes a JWT by adding its jti to the Redis revocation list with a TTL
     * matching the remaining JWT lifetime.
     *
     * @param jti       the JWT ID claim (RFC 7519 section 4.1.7)
     * @param expiresAt the JWT's expiration time
     */
    void revoke(String jti, Instant expiresAt);
}
