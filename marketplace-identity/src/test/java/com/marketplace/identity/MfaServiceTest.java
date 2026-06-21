package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class MfaServiceTest {

    @Mock private MfaSecretRepository mfaSecretRepository;
    @Mock private RecoveryCodeRepository recoveryCodeRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthAuditService auditService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Cannot use @InjectMocks — constructor has @Value String parameter that Mockito can't resolve.
        mfaService = new MfaService(mfaSecretRepository, recoveryCodeRepository,
                passwordEncoder, auditService, redisTemplate, "Marketplace");
    }

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

    @Test
    void verifyRecoveryCode_returnsTrueWhenAtomicClaimSucceeds() {
        UUID userId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        RecoveryCode rc = mock(RecoveryCode.class);
        when(rc.getId()).thenReturn(codeId);
        when(rc.getCodeHash()).thenReturn("hashed-code");
        when(recoveryCodeRepository.findByUserIdAndUsedFalse(userId)).thenReturn(List.of(rc));
        when(passwordEncoder.matches("PLAIN", "hashed-code")).thenReturn(true);
        // Atomic claim returns 1 row updated — single-use successfully enforced.
        when(recoveryCodeRepository.claimIfUnused(codeId)).thenReturn(1);

        assertTrue(mfaService.verifyRecoveryCode(userId, "PLAIN"));
        verify(recoveryCodeRepository).claimIfUnused(codeId);
        // The old markUsed() + save() pattern must NOT be called.
        verify(rc, never()).markUsed();
        verify(recoveryCodeRepository, never()).save(any());
    }

    @Test
    void verifyRecoveryCode_returnsFalseWhenConcurrentClaimWins() {
        // Simulate a concurrent request that already claimed the same code.
        UUID userId = UUID.randomUUID();
        UUID codeId = UUID.randomUUID();
        RecoveryCode rc = mock(RecoveryCode.class);
        when(rc.getId()).thenReturn(codeId);
        when(rc.getCodeHash()).thenReturn("hashed-code");
        when(recoveryCodeRepository.findByUserIdAndUsedFalse(userId)).thenReturn(List.of(rc));
        when(passwordEncoder.matches("PLAIN", "hashed-code")).thenReturn(true);
        // Atomic claim returns 0 rows — another concurrent request already claimed it.
        when(recoveryCodeRepository.claimIfUnused(codeId)).thenReturn(0);

        assertFalse(mfaService.verifyRecoveryCode(userId, "PLAIN"),
                "If a concurrent request already claimed the code, this call must fail (single-use)");
        verify(recoveryCodeRepository).claimIfUnused(codeId);
    }

    // ===== TOTP replay protection tests (RFC 6238 §5.2) =====

    /**
     * Helper: generate a valid TOTP code for the given secret at the current timestep.
     * Uses reflection to call the private generateCode method, or we can compute
     * a known-valid code by calling validateCodeWithTimestep and extracting the timestep.
     */
    private String generateValidTotpCode(String base64Secret) {
        // Compute the current TOTP code using the same algorithm as TotpService.
        long currentTime = System.currentTimeMillis() / 1000;
        long currentStep = currentTime / 30;
        try {
            java.lang.reflect.Method m = TotpService.class.getDeclaredMethod("generateCode", String.class, long.class);
            m.setAccessible(true);
            return (String) m.invoke(null, base64Secret, currentStep);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verifyTotp_acceptsFirstUseAndRejectsReplay() {
        UUID userId = UUID.randomUUID();
        String secret = TotpService.generateSecret();
        MfaSecret mfaSecret = MfaSecret.create(userId, secret);
        mfaSecret.enable();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(mfaSecret));

        String validCode = generateValidTotpCode(secret);

        // First use: Redis SETNX returns true (key was absent) → accepted.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        assertTrue(mfaService.verifyTotp(userId, validCode),
                "First use of a valid TOTP code must succeed");

        // Replay: Redis SETNX returns false (key already exists) → rejected.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);
        assertFalse(mfaService.verifyTotp(userId, validCode),
                "Replay of the same TOTP code within the window must be rejected (RFC 6238 §5.2)");
    }

    @Test
    void verifyTotp_returnsFalseWhenMfaNotEnabled() {
        UUID userId = UUID.randomUUID();
        MfaSecret mfaSecret = MfaSecret.create(userId, "secret");
        // NOT enabled
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(mfaSecret));

        assertFalse(mfaService.verifyTotp(userId, "123456"));
        // Redis must not be touched when MFA is not enabled.
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void verifyTotp_returnsFalseWhenNoSecretFound() {
        UUID userId = UUID.randomUUID();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertFalse(mfaService.verifyTotp(userId, "123456"));
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void verifyTotp_returnsFalseForInvalidCode() {
        UUID userId = UUID.randomUUID();
        String secret = TotpService.generateSecret();
        MfaSecret mfaSecret = MfaSecret.create(userId, secret);
        mfaSecret.enable();
        when(mfaSecretRepository.findByUserId(userId)).thenReturn(Optional.of(mfaSecret));

        // "000000" is almost certainly not the valid code (1 in 10^6 chance).
        assertFalse(mfaService.verifyTotp(userId, "000000"));
        // Redis must not be touched when the code is invalid.
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }
}
