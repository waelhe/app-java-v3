package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 16;

    /** Redis key prefix for used TOTP timesteps (replay protection). */
    private static final String USED_TIMESTEP_KEY_PREFIX = "marketplace:mfa:used-timestep:";

    /**
     * TTL for used-timestep markers: 3 timesteps x 30s = 90 seconds.
     * This covers the full +/-1 validation window -- after 90s the timestep can
     * never match again (it's outside the window), so the marker is safe to expire.
     */
    private static final Duration USED_TIMESTEP_TTL = Duration.ofSeconds(90);

    private final MfaSecretRepository mfaSecretRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService auditService;
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;

    private final String issuer;

    public MfaService(MfaSecretRepository mfaSecretRepository,
                       RecoveryCodeRepository recoveryCodeRepository,
                       PasswordEncoder passwordEncoder,
                       AuthAuditService auditService,
                       StringRedisTemplate redisTemplate,
                       UserRepository userRepository,
                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.mfa.issuer:Marketplace}") String issuer) {
        this.mfaSecretRepository = mfaSecretRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.issuer = issuer;
    }

    /**
     * Initiates MFA setup -- generates a new TOTP secret.
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

        auditService.log(username, AuthEventType.MFA_ENABLED, "MFA enabled");

        return codes;
    }

    /**
     * Disables MFA after password verification.
     */
    public void disableMfa(UUID userId, String username) {
        MfaSecret mfaSecret = mfaSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("MFA is not enabled"));
        // Note: Password verification should be done by caller before calling this method.
        // This follows OWASP MFA Cheat Sheet: require re-authentication before MFA changes.

        mfaSecret.disable();
        mfaSecretRepository.delete(mfaSecret);

        // Delete all recovery codes
        List<RecoveryCode> codes = recoveryCodeRepository.findByUserIdAndUsedFalse(userId);
        recoveryCodeRepository.deleteAll(codes);

        auditService.log(username, AuthEventType.MFA_DISABLED, "MFA disabled");
    }

    /**
     * Verifies a TOTP code for login (called during authentication).
     *
     * <p>Uses {@code @Transactional(readOnly = true)} -- the method only reads from JPA
     * (mfaSecretRepository.findByUserId, userRepository.findById) and writes to Redis
     * (setIfAbsent). Redis operations are NOT transactional with JPA, so a read-write
     * JPA transaction adds overhead (connection acquisition, commit) with no benefit.
     *
     * <p><b>Replay protection</b> (RFC 6238 section5.2 step 4): "The verifier MUST NOT
     * accept the second attempt of the OTP after the successful validation has
     * been issued." After a successful validation, the matched timestep is
     * atomically claimed in Redis (SETNX with TTL). Any subsequent attempt
     * using the same timestep is rejected.
     *
     * @return true if the code is valid AND has not been replayed
     */
    @Transactional(readOnly = true)
    public boolean verifyTotp(UUID userId, String code) {
        Optional<String> secretOpt = mfaSecretRepository.findByUserId(userId)
                .filter(MfaSecret::isEnabled)
                .map(MfaSecret::getSecret);
        if (secretOpt.isEmpty()) {
            return false;
        }

        Optional<Long> timestepOpt = TotpService.validateCodeWithTimestep(secretOpt.get(), code);
        if (timestepOpt.isEmpty()) {
            return false;
        }

        long timestep = timestepOpt.get();
        String key = USED_TIMESTEP_KEY_PREFIX + userId + ":" + timestep;

        // Atomic claim: SETNX returns true if the key was set (first use),
        // false if it already existed (replay attempt).
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", USED_TIMESTEP_TTL);
        if (!Boolean.TRUE.equals(claimed)) {
            // Resolve the username for the audit log -- never log null username.
            String username = userRepository.findById(userId)
                    .map(User::getEmail)
                    .orElse("unknown-user-" + userId);
            log.warn("TOTP replay detected: user={}, timestep={}", userId, timestep);
            auditService.log(username, AuthEventType.MFA_FAILURE,
                    "TOTP replay rejected for timestep " + timestep);
            return false;
        }

        return true;
    }

    /**
     * Verifies a recovery code and marks it as used.
     *
     * <p><b>Atomicity</b>: the claim is performed via
     * {@link RecoveryCodeRepository#claimIfUnused(UUID)}, a single conditional
     * UPDATE that returns 1 only if the row was previously unused. This closes
     * the race where two concurrent requests with the same valid code both
     * pass the in-memory check and both consume the code (single-use violation).
     *
     * <p><b>References</b>
     * <ul>
     *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet -- recovery codes must be single-use</a></li>
     *   <li><a href="https://www.postgresql.org/docs/current/sql-update.html">PostgreSQL UPDATE -- row-level locking</a></li>
     * </ul>
     *
     * @return true if the code was valid and successfully claimed (single-use enforced)
     */
    public boolean verifyRecoveryCode(UUID userId, String code) {
        List<RecoveryCode> codes = recoveryCodeRepository.findByUserIdAndUsedFalse(userId);
        for (RecoveryCode rc : codes) {
            if (passwordEncoder.matches(code, rc.getCodeHash())) {
                // Atomic single-use claim -- returns 0 if a concurrent request already claimed it.
                int rowsAffected = recoveryCodeRepository.claimIfUnused(rc.getId());
                return rowsAffected == 1;
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
