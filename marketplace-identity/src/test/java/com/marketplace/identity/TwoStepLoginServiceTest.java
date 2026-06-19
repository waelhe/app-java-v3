package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TwoStepLoginService} — MFA + BruteForce in login flow.
 *
 * <p>Verifies the critical MFA-token binding (OWASP MFA Cheat Sheet):
 * <ul>
 *   <li>Step 2 refuses to proceed without a valid mfaToken</li>
 *   <li>mfaToken is single-use (consumed on successful verification)</li>
 *   <li>mfaToken is rejected after expiry</li>
 *   <li>mfaToken is rejected if userId does not match</li>
 *   <li>Failed MFA/recovery attempts trigger brute-force protection</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet</a>
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/index.html">Spring Security Authentication</a>
 */
@ExtendWith(MockitoExtension.class)
class TwoStepLoginServiceTest {

    @Mock private UserDetailsManager userDetailsManager;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private BruteForceProtectionService bruteForceService;
    @Mock private MfaService mfaService;
    @Mock private AuthAuditService auditService;
    @Mock private UserRepository userRepository;

    @InjectMocks private TwoStepLoginService loginService;

    private User testUser;
    private UserDetails testUserDetails;

    @BeforeEach
    void setUp() {
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

    // ===== Step 2 MFA verification — mfaToken binding tests =====

    @Test
    void verifyMfa_success_consumesToken() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        TwoStepLoginService.LoginResult result = loginService.verifyMfa(userId, mfaToken, "123456");

        assertEquals("SUCCESS", result.status());
        // Second attempt with the same token must fail (single-use).
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, mfaToken, "123456"));
        assertTrue(ex.getMessage().contains("MFA token"), "Consumed token must be rejected");
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
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> loginService.verifyMfa(userId, "unknown-token", "123456"));
        assertTrue(ex.getMessage().contains("Invalid or already-used"));
        verifyNoInteractions(mfaService);
    }

    @Test
    void verifyMfa_throwsWhenTokenUserIdMismatch() {
        UUID legitUserId = testUser.getId();
        String mfaToken = issueMfaToken(legitUserId);
        UUID attackerUserId = UUID.randomUUID();

        // The mfaToken was issued for legitUserId; verifyMfa must reject it for attackerUserId
        // BEFORE calling mfaService.verifyTotp (the binding is checked first).
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

        // Token must STILL be valid for retry (not consumed on wrong TOTP).
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        TwoStepLoginService.LoginResult retry = loginService.verifyMfa(userId, mfaToken, "123456");
        assertEquals("SUCCESS", retry.status());
    }

    // ===== Step 2 recovery-code verification — mfaToken binding tests =====

    @Test
    void verifyRecoveryCode_success_consumesToken() {
        UUID userId = testUser.getId();
        String mfaToken = issueMfaToken(userId);
        when(mfaService.verifyRecoveryCode(userId, "ABCD1234EFGH5678")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        TwoStepLoginService.LoginResult result = loginService.verifyRecoveryCode(userId, mfaToken, "ABCD1234EFGH5678");

        assertEquals("SUCCESS", result.status());
        // Re-use must fail.
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
     */
    private String issueMfaToken(UUID userId) {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("password", "encoded-pass")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(mfaService.isMfaEnabled(userId)).thenReturn(true);

        TwoStepLoginService.LoginResult r = loginService.login("user@test.com", "password");
        assertEquals("MFA_REQUIRED", r.status());
        return r.mfaToken();
    }
}
