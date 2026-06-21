package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MfaService}.
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA</a>
 */
@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock
    private MfaSecretRepository mfaSecretRepository;
    @Mock
    private RecoveryCodeRepository recoveryCodeRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthAuditService auditService;

    @InjectMocks
    private MfaService mfaService;

    @Test
    void setupMfa_returnsSecretAndUri() {
        UUID userId = UUID.randomUUID();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(mfaSecretRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        MfaService.MfaSetupResponse result = mfaService.setupMfa(userId, "user@test.com");

        assertNotNull(result);
        assertNotNull(result.secret());
        assertTrue(result.otpAuthUri().startsWith("otpauth://totp/"));
    }

    @Test
    void setupMfa_throwsWhenAlreadyEnabled() {
        UUID userId = UUID.randomUUID();
        MfaSecret existing = MfaSecret.create(userId, "secret");
        existing.enable();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> mfaService.setupMfa(userId, "user@test.com"));
    }

    @Test
    void verifyAndEnableMfa_throwsWhenNotSetup() {
        UUID userId = UUID.randomUUID();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> mfaService.verifyAndEnableMfa(userId, "123456", "user@test.com"));
    }

    @Test
    void verifyAndEnableMfa_throwsWhenAlreadyEnabled() {
        UUID userId = UUID.randomUUID();
        MfaSecret existing = MfaSecret.create(userId, "secret");
        existing.enable();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class,
                () -> mfaService.verifyAndEnableMfa(userId, "123456", "user@test.com"));
    }

    @Test
    void verifyAndEnableMfa_throwsWhenInvalidCode() {
        UUID userId = UUID.randomUUID();
        MfaSecret existing = MfaSecret.create(userId, TotpService.generateSecret());
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class,
                () -> mfaService.verifyAndEnableMfa(userId, "000000", "user@test.com"));
    }

    @Test
    void disableMfa_throwsWhenNotEnabled() {
        UUID userId = UUID.randomUUID();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> mfaService.disableMfa(userId, "user@test.com"));
    }

    @Test
    void isMfaEnabled_returnsFalseWhenNoSecret() {
        UUID userId = UUID.randomUUID();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertFalse(mfaService.isMfaEnabled(userId));
    }

    @Test
    void isMfaEnabled_returnsFalseWhenDisabled() {
        UUID userId = UUID.randomUUID();
        MfaSecret secret = MfaSecret.create(userId, "secret");
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(secret));

        assertFalse(mfaService.isMfaEnabled(userId));
    }

    @Test
    void isMfaEnabled_returnsTrueWhenEnabled() {
        UUID userId = UUID.randomUUID();
        MfaSecret secret = MfaSecret.create(userId, "secret");
        secret.enable();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(secret));

        assertTrue(mfaService.isMfaEnabled(userId));
    }

    @Test
    void verifyRecoveryCode_returnsFalseWhenNoMatch() {
        UUID userId = UUID.randomUUID();
        when(recoveryCodeRepository.findByUserIdAndUsedFalse(userId)).thenReturn(List.of());

        assertFalse(mfaService.verifyRecoveryCode(userId, "CODE1234"));
    }
}
