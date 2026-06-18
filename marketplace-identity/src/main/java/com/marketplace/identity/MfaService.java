package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages MFA (Multi-Factor Authentication) lifecycle.
 * <p>Follows OWASP MFA Cheat Sheet:
 * <ul>
 *   <li>TOTP via RFC 6238 (Google Authenticator compatible)</li>
 *   <li>Recovery codes: 10 single-use codes, stored as hashes</li>
 *   <li>Setup requires verification before enabling</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238</a>
 */
@Service
@Transactional
public class MfaService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 16;

    private final MfaSecretRepository mfaSecretRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService auditService;

    @Value("${marketplace.security.mfa.issuer:Marketplace}")
    private String issuer;

    public MfaService(MfaSecretRepository mfaSecretRepository,
                       RecoveryCodeRepository recoveryCodeRepository,
                       PasswordEncoder passwordEncoder,
                       AuthAuditService auditService) {
        this.mfaSecretRepository = mfaSecretRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Initiates MFA setup — generates a new TOTP secret.
     * Returns the secret + otpauth URI for QR code display.
     * MFA is NOT enabled until verified.
     */
    public MfaSetupResponse setupMfa(UUID userId, String accountEmail) {
        // Delete existing unverified secret if any
        mfaSecretRepository.findByUserId(userId).ifPresent(existing -> {
            if (!existing.isEnabled()) {
                mfaSecretRepository.delete(existing);
            } else {
                throw new BadRequestException("MFA is already enabled. Disable it first.");
            }
        });

        String secret = TotpService.generateSecret();
        MfaSecret mfaSecret = MfaSecret.create(userId, secret);
        mfaSecretRepository.save(mfaSecret);

        String otpAuthUri = TotpService.buildOtpAuthUri(secret, accountEmail, issuer);

        return new MfaSetupResponse(secret, otpAuthUri);
    }

    /**
     * Verifies a TOTP code and enables MFA.
     * Generates recovery codes upon successful enable.
     */
    public List<String> verifyAndEnableMfa(UUID userId, String code, String username) {
        MfaSecret mfaSecret = mfaSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("MFA setup not initiated"));

        if (mfaSecret.isEnabled()) {
            throw new BadRequestException("MFA is already enabled");
        }

        if (!TotpService.validateCode(mfaSecret.getSecret(), code)) {
            throw new BadRequestException("Invalid verification code");
        }

        mfaSecret.enable();
        mfaSecretRepository.save(mfaSecret);

        List<String> codes = generateRecoveryCodes(userId);

        auditService.log(username, AuthEventType.PASSWORD_CHANGED, "MFA enabled");

        return codes;
    }

    /**
     * Disables MFA after password verification.
     */
    public void disableMfa(UUID userId, String username) {
        MfaSecret mfaSecret = mfaSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("MFA is not enabled"));

        mfaSecret.disable();
        mfaSecretRepository.delete(mfaSecret);

        // Delete all recovery codes
        List<RecoveryCode> codes = recoveryCodeRepository.findByUserIdAndUsedFalse(userId);
        recoveryCodeRepository.deleteAll(codes);

        auditService.log(username, AuthEventType.PASSWORD_CHANGED, "MFA disabled");
    }

    /**
     * Verifies a TOTP code for login (called during authentication).
     */
    @Transactional(readOnly = true)
    public boolean verifyTotp(UUID userId, String code) {
        return mfaSecretRepository.findByUserId(userId)
                .filter(MfaSecret::isEnabled)
                .map(mfa -> TotpService.validateCode(mfa.getSecret(), code))
                .orElse(false);
    }

    /**
     * Verifies a recovery code and marks it as used.
     */
    public boolean verifyRecoveryCode(UUID userId, String code) {
        List<RecoveryCode> codes = recoveryCodeRepository.findByUserIdAndUsedFalse(userId);
        for (RecoveryCode rc : codes) {
            if (passwordEncoder.matches(code, rc.getCodeHash())) {
                rc.markUsed();
                recoveryCodeRepository.save(rc);
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if MFA is enabled for a user.
     */
    @Transactional(readOnly = true)
    public boolean isMfaEnabled(UUID userId) {
        return mfaSecretRepository.findByUserId(userId)
                .map(MfaSecret::isEnabled)
                .orElse(false);
    }

    private List<String> generateRecoveryCodes(UUID userId) {
        List<String> plainCodes = new ArrayList<>();
        List<RecoveryCode> entities = new ArrayList<>();

        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = generateRecoveryCode();
            plainCodes.add(code);
            String hash = passwordEncoder.encode(code);
            entities.add(RecoveryCode.create(userId, hash));
        }

        recoveryCodeRepository.saveAll(entities);
        return plainCodes;
    }

    private String generateRecoveryCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(RECOVERY_CODE_LENGTH);
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public record MfaSetupResponse(String secret, String otpAuthUri) {
    }
}
