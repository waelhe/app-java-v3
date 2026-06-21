package com.marketplace.shared.security.oauth2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.marketplace.shared.api.JwtRevocationPort;

import java.time.Duration;
import java.time.Instant;

/**
 * Validates that a JWT has not been revoked via the Redis-based revocation list.
 *
 * <p>When a user clicks "Log out everywhere" (SessionController.revokeAllSessions),
 * the JWT's {@code jti} (JWT ID) claim is added to a Redis SET with a TTL matching
 * the remaining JWT lifetime. This validator checks that SET on every authenticated
 * request and rejects revoked tokens.
 *
 * <p><b>Why this is needed</b>: JWTs issued directly by TwoStepLoginService.issueJwt
 * and OAuth2LoginSuccessHandler are NOT recorded in the oauth2_authorization table
 * (only Spring Authorization Server's /oauth2/token endpoint writes there). Without
 * this validator, "Log out everywhere" revokes nothing for password/social-login users.
 *
 * <p><b>Reference</b>: Spring Security Reference -- JWT Resource Server:
 * "Resource Server ships with two standard validators and also accepts custom
 * OAuth2TokenValidator instances."
 * RFC 9068 section 3: "JWTs are stateless and require a server-side revocation list
 * to invalidate before exp."
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security JWT Resource Server</a>
 */
@Component
public class JwtRevocationValidator implements OAuth2TokenValidator<Jwt>, JwtRevocationPort {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationValidator.class);

    /** Redis key prefix for the revoked JWT set. */
    static final String REVOCATION_KEY_PREFIX = "marketplace:jwt:revoked:";

    private final StringRedisTemplate redisTemplate;

    public JwtRevocationValidator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if (jti == null) {
            // JWT without jti claim -- cannot check revocation. Allow (the issuer
            // should always set jti per RFC 7519 section 4.1.7, but we fail open
            // for compatibility with JWTs that lack it).
            return OAuth2TokenValidatorResult.success();
        }

        String revocationKey = REVOCATION_KEY_PREFIX + jti;
        try {
            Boolean isRevoked = redisTemplate.hasKey(revocationKey);
            if (Boolean.TRUE.equals(isRevoked)) {
                log.warn("JWT rejected -- revoked: jti={}", jti);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "The token has been revoked",
                        "https://datatracker.ietf.org/doc/html/rfc7009"));
            }
        } catch (Exception e) {
            // Redis unavailable -- fail open (allow the token). Rationale: rejecting all
            // valid tokens during a Redis outage would lock out every user. The token's
            // signature, issuer, audience, and expiry are still validated by the standard
            // validators. Revocation is a secondary defense, not the primary one.
            log.warn("JWT revocation check failed for jti={} -- failing open (Redis unavailable?)", jti, e);
        }

        return OAuth2TokenValidatorResult.success();
    }

    /**
     * Adds a JWT's jti to the revocation list with a TTL matching the remaining lifetime.
     * Called by SessionController.revokeAllSessions for each active JWT.
     *
     * @param jti     the JWT ID claim
     * @param expiresAt the JWT's expiration time
     */
    @Override
    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        Duration remainingTtl = Duration.between(Instant.now(), expiresAt);
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            // Already expired -- no need to revoke
            return;
        }
        String key = REVOCATION_KEY_PREFIX + jti;
        redisTemplate.opsForValue().set(key, "1", remainingTtl);
        log.info("JWT revoked: jti={}, ttl={}s", jti, remainingTtl.getSeconds());
    }
}
