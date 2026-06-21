package com.marketplace.shared.security.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtRevocationValidatorTest {

    @Mock private StringRedisTemplate redisTemplate;

    private JwtRevocationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JwtRevocationValidator(redisTemplate);
    }

    private Jwt mockJwt(String jti, Instant expiresAt) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("jti", jti)
                .issuedAt(Instant.now())
                .expiresAt(expiresAt)
                .issuer("http://localhost:8080")
                .audience(List.of("marketplace-api"))
                .build();
    }

    @Test
    void validate_allowsNonRevokedToken() {
        Jwt jwt = mockJwt("jti-123", Instant.now().plus(1, ChronoUnit.HOURS));
        when(redisTemplate.hasKey("marketplace:jwt:revoked:jti-123")).thenReturn(false);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.getErrors().isEmpty(), "Non-revoked token should pass validation");
    }

    @Test
    void validate_rejectsRevokedToken() {
        Jwt jwt = mockJwt("jti-456", Instant.now().plus(1, ChronoUnit.HOURS));
        when(redisTemplate.hasKey("marketplace:jwt:revoked:jti-456")).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertEquals(1, result.getErrors().size(), "Revoked token should have 1 error");
        assertEquals(OAuth2ErrorCodes.INVALID_TOKEN, result.getErrors().iterator().next().getErrorCode());
    }

    @Test
    void validate_allowsTokenWithoutJti() {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .issuer("http://localhost:8080")
                .audience(List.of("marketplace-api"))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.getErrors().isEmpty(), "Token without jti should pass (fail-open)");
    }

    @Test
    void validate_failsOpenOnRedisError() {
        Jwt jwt = mockJwt("jti-789", Instant.now().plus(1, ChronoUnit.HOURS));
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis down"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.getErrors().isEmpty(),
                "Must fail open on Redis error -- never lock out all users on infra failure");
    }


    @Test
    void revoke_skipsAlreadyExpiredToken() {
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.MINUTES);

        validator.revoke("jti-expired", pastExpiry);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revoke_skipsNullJti() {
        validator.revoke(null, Instant.now().plus(1, ChronoUnit.HOURS));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revoke_skipsNullExpiry() {
        validator.revoke("jti-123", null);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revoke_addsJtiToRedisWithRemainingTtl() {
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        validator.revoke("jti-revoke", expiresAt);

        verify(valueOps).set(eq("marketplace:jwt:revoked:jti-revoke"), eq("1"), any(java.time.Duration.class));
    }
}
