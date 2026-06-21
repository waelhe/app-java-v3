package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TwoStepLoginService} - MFA + BruteForce in login flow.
 *
 * <p>Verifies the critical MFA-token binding (OWASP MFA Cheat Sheet):
 * <ul>
 *   <li>Step 2 refuses to proceed without a valid mfaToken</li>
 *   <li>mfaToken is single-use (consumed on successful verification via Redis DELETE)</li>
 *   <li>mfaToken is rejected after expiry (Redis TTL handles this automatically)</li>
 *   <li>mfaToken is rejected if userId does not match</li>
 *   <li>Concurrent consume attempts: only the first succeeds (Redis DELETE atomicity)</li>
 *   <li>Failed MFA/recovery attempts trigger brute-force protection</li>
 * </ul>
 *
 * <p>Uses mocked {@link StringRedisTemplate} - no real Redis connection needed for unit tests.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet</a>
 * @see <a href="https://docs.spring.io/spring-data/redis/reference/">Spring Data Redis Reference</a>
 */
@ExtendWith(MockitoExtension.class)
class TwoStepLoginServiceTest {

    @Mock private UserDetailsManager userDetailsManager;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private BruteForceProtectionService bruteForceService;
    @Mock private MfaService mfaService;
    @Mock private AuthAuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    @Mock private com.marketplace.shared.config.MarketplaceProperties properties;

    private TwoStepLoginService loginService;

    private User testUser;
    private UserDetails testUserDetails;

    @BeforeEach
    void setUp() {
        // Cannot use @InjectMocks reliably with the redisTemplate mock + ValueOperations chain.
        // Construct manually for clarity.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Stub MarketplaceProperties for JWT issuance.
        com.marketplace.shared.config.MarketplaceProperties.Security.AuthServer authServer =
                mock(com.marketplace.shared.config.MarketplaceProperties.Security.AuthServer.class);
        com.marketplace.shared.config.MarketplaceProperties.Security security =
                mock(com.marketplace.shared.config.MarketplaceProperties.Security.class);
        com.marketplace.shared.config.MarketplaceProperties.Security.Jwt jwt =
                mock(com.marketplace.shared.config.MarketplaceProperties.Security.Jwt.class);
        lenient().when(properties.security()).thenReturn(security);
        lenient().when(security.authServer()).thenReturn(authServer);
        lenient().when(authServer.issuer()).thenReturn("http://localhost:8080");
        lenient().when(security.jwt()).thenReturn(jwt);
        lenient().when(jwt.audience()).thenReturn("marketplace-api");

        // Stub JwtEncoder to return a dummy JWT.
        org.springframework.security.oauth2.jwt.Jwt mockJwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .claim("sub", "ignored")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .issuer("http://localhost:8080")
                .audience(java.util.List.of("marketplace-api"))
                .build();
        lenient().when(jwtEncoder.encode(any())).thenReturn(mockJwt);

        loginService = new TwoStepLoginService(
                userDetailsManager, passwordEncoder, bruteForceService,
                mfaService, auditService, userRepository, redisTemplate, jwtEncoder, properties);

        testUser = User.create("sub-1", "user@test.com", "User", UserRole.CONSUMER);
        testUserDetails = org.springframework.security.core.userdetails.User.withUsername("user@test.com")
                .password("encoded-pass")
                .roles("CONSUMER")
                .disabled(false)
                .build();
    }

    @Test
    void login_successWithoutMfa() {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("password", "encoded-pass")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(mfaService.isMfaEnabled(testUser.getId())).thenReturn(false);

        TwoStepLoginService.LoginResult result = loginService.login("user@test.com", "password");

        assertEquals("SUCCESS", result.status());
        verify(bruteForceService).resetFailedAttempts("user@test.com");
        verify(auditService).log("user@test.com", AuthEventType.LOGIN_SUCCESS, "Login successful (step 1)");
        // No Redis interaction for non-MFA login.
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void login_requiresMfa_returnsMfaTokenForStep2() {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("password", "encoded-pass")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(mfaService.isMfaEnabled(testUser.getId())).thenReturn(true);

        TwoStepLoginService.LoginResult result = loginService.login("user@test.com", "password");

        assertEquals("MFA_REQUIRED", result.status());
        assertNotNull(result.mfaToken());
        assertEquals(36, result.mfaToken().length(), "mfaToken must be a UUID string");
        // Verify the token was stored in Redis with TTL.
        verify(valueOperations).set(eq("marketplace:mfa:pending:" + result.mfaToken()),
                eq(testUser.getId().toString()),
                eq(Duration.ofMinutes(5)));
    }

    @Test
    void login_throwsWhenAccountLocked() {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> loginService.login("user@test.com", "password"));
        verify(userDetailsManager, never()).loadUserByUsername(any());
    }

    @Test
    void login_throwsWhenWrongPassword() {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("wrong", "encoded-pass")).thenReturn(false);

        assertThrows(BadRequestException.class, () -> loginService.login("user@test.com", "wrong"));
        verify(bruteForceService).recordFailedAttempt("user@test.com");
    }

    @Test
    void login_throwsWhenAccountDisabled() {
        UserDetails disabledUser = org.springframework.security.core.userdetails.User.withUsername("user@test.com")
                .password("encoded-pass")
                .roles("CONSUMER")
                .disabled(true)
                .build();
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(disabledUser);

        assertThrows(BadRequestException.class, () -> loginService.login("user@test.com", "password"));
    }

    // ===== Step 2 MFA verification - mfaToken binding tests =====

    @Test
    void verifyMfa_success_consumesToken() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        // Redis DELETE returns true - token successfully consumed.
        when(redisTemplate.delete("marketplace:mfa:pending:" + mfaToken)).thenReturn(true);

        TwoStepLoginService.LoginResult result = loginService.verifyMfa(userId, mfaToken, "123456");

        assertEquals("SUCCESS", result.status());
        // Second attempt: Redis GET now returns null (token was deleted) -> rejected.
        when(valueOperations.get("marketplace:mfa:pending:" + mfaToken)).thenReturn(null);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, mfaToken, "123456"));
        assertTrue(ex.getMessage().contains("Invalid, already-used, or expired"));
    }

    @Test
    void verifyMfa_throwsWhenTokenMissing() {
        UUID userId = UUID.randomUUID();
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, null, "123456"));
        assertTrue(ex.getMessage().contains("Missing MFA token"));
        verifyNoInteractions(mfaService);
    }

    @Test
    void verifyMfa_throwsWhenTokenUnknown() {
        UUID userId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, "unknown-token", "123456"));
        assertTrue(ex.getMessage().contains("Invalid, already-used, or expired"));
        verifyNoInteractions(mfaService);
    }

    @Test
    void verifyMfa_throwsWhenTokenUserIdMismatch() {
        UUID legitUserId = testUser.getId();
        String mfaToken = issueMfaToken(legitUserId);
        UUID attackerUserId = UUID.randomUUID();

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(attackerUserId, mfaToken, "123456"));
        assertTrue(ex.getMessage().contains("does not match"));
        // verifyTotp must never have been called with the attacker's userId.
        verify(mfaService, never()).verifyTotp(attackerUserId, "123456");
    }

    @Test
    void verifyMfa_throwsWhenInvalidCode_doesNotConsumeToken() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyTotp(userId, "000000")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        assertThrows(BadRequestException.class, () -> loginService.verifyMfa(userId, mfaToken, "000000"));
        verify(bruteForceService).recordFailedAttempt("user@test.com");
        // Token must NOT have been consumed (DELETE not called on wrong TOTP).
        verify(redisTemplate, never()).delete(anyString());

        // Token must STILL be valid for retry (not consumed on wrong TOTP).
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        when(redisTemplate.delete("marketplace:mfa:pending:" + mfaToken)).thenReturn(true);
        TwoStepLoginService.LoginResult retry = loginService.verifyMfa(userId, mfaToken, "123456");
        assertEquals("SUCCESS", retry.status());
    }

    @Test
    void verifyMfa_throwsWhenConcurrentRequestAlreadyConsumed() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        // Redis DELETE returns false - a concurrent request already consumed the token.
        // Note: userRepository.findById is NOT stubbed because the consume check fails
        // before we reach the user lookup - keeping the test honest about the flow.
        when(redisTemplate.delete("marketplace:mfa:pending:" + mfaToken)).thenReturn(false);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, mfaToken, "123456"));
        assertTrue(ex.getMessage().contains("already used"),
                "Concurrent consume must be rejected: " + ex.getMessage());
    }

    // ===== Step 2 recovery-code verification - mfaToken binding tests =====

    @Test
    void verifyRecoveryCode_success_consumesToken() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyRecoveryCode(userId, "ABCD1234EFGH5678")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(redisTemplate.delete("marketplace:mfa:pending:" + mfaToken)).thenReturn(true);

        TwoStepLoginService.LoginResult result = loginService.verifyRecoveryCode(userId, mfaToken, "ABCD1234EFGH5678");

        assertEquals("SUCCESS", result.status());
        // Re-use must fail - Redis GET returns null after DELETE.
        when(valueOperations.get("marketplace:mfa:pending:" + mfaToken)).thenReturn(null);
        assertThrows(BadRequestException.class,
                () -> loginService.verifyRecoveryCode(userId, mfaToken, "ABCD1234EFGH5678"));
    }

    @Test
    void verifyRecoveryCode_throwsWhenTokenMissing() {
        UUID userId = UUID.randomUUID();
        assertThrows(BadRequestException.class,
                () -> loginService.verifyRecoveryCode(userId, null, "ABCD1234EFGH5678"));
        verifyNoInteractions(mfaService);
    }

    @Test
    void verifyRecoveryCode_throwsWhenInvalid() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyRecoveryCode(userId, "WRONGWRONGWRONG")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        assertThrows(BadRequestException.class,
                () -> loginService.verifyRecoveryCode(userId, mfaToken, "WRONGWRONGWRONG"));
        verify(bruteForceService).recordFailedAttempt("user@test.com");
    }

    /**
     * Helper: drive through step 1 to obtain a real mfaToken from the service.
     * Stubs the Redis SET call and returns the issued token.
     */
    private String issueMfaToken(UUID userId) {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("password", "encoded-pass")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(mfaService.isMfaEnabled(userId)).thenReturn(true);

        TwoStepLoginService.LoginResult r = loginService.login("user@test.com", "password");
        assertEquals("MFA_REQUIRED", r.status());
        // Stub the Redis GET for subsequent step-2 calls - returns the user's UUID string.
        when(valueOperations.get("marketplace:mfa:pending:" + r.mfaToken()))
                .thenReturn(userId.toString());
        return r.mfaToken();
    }
}
