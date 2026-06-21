package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
// removed — conflicts with com.marketplace.identity.User
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

    private com.marketplace.identity.User testUser;
    private org.springframework.security.core.userdetails.UserDetails testUserDetails;

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
        verify(auditService).log("user@test.com", AuthEventType.LOGIN_SUCCESS, "Login successful");
    }

    @Test
    void login_requiresMfa() {
        when(bruteForceService.isLocked("user@test.com")).thenReturn(false);
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(testUserDetails);
        when(passwordEncoder.matches("password", "encoded-pass")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(mfaService.isMfaEnabled(testUser.getId())).thenReturn(true);

        TwoStepLoginService.LoginResult result = loginService.login("user@test.com", "password");

        assertEquals("MFA_REQUIRED", result.status());
        assertNotNull(result.mfaToken());
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

    @Test
    void verifyMfa_success() {
        UUID userId = UUID.randomUUID();
        when(mfaService.verifyTotp(userId, "123456")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        TwoStepLoginService.LoginResult result = loginService.verifyMfa(userId, "123456");

        assertEquals("SUCCESS", result.status());
        verify(auditService).log(any(), eq(AuthEventType.LOGIN_SUCCESS), any());
    }

    @Test
    void verifyMfa_throwsWhenInvalidCode() {
        UUID userId = UUID.randomUUID();
        when(mfaService.verifyTotp(userId, "000000")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        assertThrows(BadRequestException.class, () -> loginService.verifyMfa(userId, "000000"));
    }

    @Test
    void verifyRecoveryCode_success() {
        UUID userId = UUID.randomUUID();
        when(mfaService.verifyRecoveryCode(userId, "ABCD1234")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        TwoStepLoginService.LoginResult result = loginService.verifyRecoveryCode(userId, "ABCD1234");

        assertEquals("SUCCESS", result.status());
    }

    @Test
    void verifyRecoveryCode_throwsWhenInvalid() {
        UUID userId = UUID.randomUUID();
        when(mfaService.verifyRecoveryCode(userId, "WRONG")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        assertThrows(BadRequestException.class, () -> loginService.verifyRecoveryCode(userId, "WRONG"));
    }
}
