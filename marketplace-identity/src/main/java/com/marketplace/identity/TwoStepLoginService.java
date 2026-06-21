package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step login flow with MFA and brute force protection integrated.
 *
 * <p>Flow:
 * <ol>
 *   <li>Step 1: {@code POST /api/v1/auth/login} → validate credentials →
 *       return {@code {status: "MFA_REQUIRED", mfaToken, userId}} OR
 *       {@code {status: "SUCCESS", userId}}</li>
 *   <li>Step 2: {@code POST /api/v1/auth/login/mfa} → validate TOTP AND
 *       the single-use {@code mfaToken} from step 1 → return SUCCESS</li>
 *   <li>Step 2 alternative: {@code POST /api/v1/auth/login/recovery-code} →
 *       validate a recovery code AND the {@code mfaToken}</li>
 * </ol>
 *
 * <p><b>Critical security binding (OWASP MFA Cheat Sheet)</b>: the
 * {@code mfaToken} returned from step 1 is single-use and expires after 5
 * minutes. Step 2 endpoints refuse to proceed without a valid, unexpired
 * token. This prevents an attacker who knows the user's UUID from skipping
 * the password and brute-forcing the TOTP directly.
 *
 * <p>Brute force protection is integrated:
 * <ul>
 *   <li>Checks {@code isLocked()} before attempting login</li>
 *   <li>Records failed attempts on step-1 authentication failure</li>
 *   <li>Records failed attempts on step-2 MFA / recovery-code failure</li>
 *   <li>Resets failed attempts on successful authentication</li>
 * </ul>
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet — "MFA must be bound to the authenticated session"</a></li>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet</a></li>
 *   <li><a href="https://docs.spring.io/spring-security/reference/servlet/authentication/index.html">Spring Security Authentication</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6238#section-5.2">RFC 6238 §5.2 — TOTP Replay Protection</a></li>
 * </ul>
 */
@Service
@Transactional
public class TwoStepLoginService {

    private static final Logger log = LoggerFactory.getLogger(TwoStepLoginService.class);

    /** MFA token validity window — 5 minutes per OWASP MFA Cheat Sheet. */
    private static final long MFA_TOKEN_TTL_SECONDS = 300;

    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final BruteForceProtectionService bruteForceService;
    private final MfaService mfaService;
    private final AuthAuditService auditService;
    private final UserRepository userRepository;

    /**
     * In-memory store of pending MFA tokens: {@code mfaToken -> {userId, expiresAt}}.
     * <p>For multi-instance deployments this should be replaced with a shared
     * store (Redis). For single-instance dev/stage this in-memory store is
     * sufficient and avoids a new infrastructure dependency for the security fix.
     * <p>Token entries are removed on first use (single-use) or on expiry.
     */
    private final Map<String, PendingMfa> pendingMfaTokens = new ConcurrentHashMap<>();

    @Autowired
    public TwoStepLoginService(UserDetailsManager userDetailsManager,
                                PasswordEncoder passwordEncoder,
                                BruteForceProtectionService bruteForceService,
                                MfaService mfaService,
                                AuthAuditService auditService,
                                UserRepository userRepository) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.bruteForceService = bruteForceService;
        this.mfaService = mfaService;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    /**
     * Step 1: Validates username/password credentials.
     * <p>Returns:
     * <ul>
     *   <li>If MFA enabled: {@code {status: "MFA_REQUIRED", mfaToken: "..."}}</li>
     *   <li>If MFA not enabled: {@code {status: "SUCCESS"}}</li>
     * </ul>
     */
    public LoginResult login(String username, String password) {
        if (bruteForceService.isLocked(username)) {
            auditService.log(username, AuthEventType.LOGIN_FAILURE, "Account locked due to brute force");
            throw new BadRequestException("Account is temporarily locked. Please try again later.");
        }

        org.springframework.security.core.userdetails.UserDetails userDetails;
        try {
            userDetails = userDetailsManager.loadUserByUsername(username);
        } catch (Exception e) {
            bruteForceService.recordFailedAttempt(username);
            auditService.log(username, AuthEventType.LOGIN_FAILURE, "User not found");
            throw new BadRequestException("Invalid credentials");
        }

        if (!userDetails.isEnabled()) {
            auditService.log(username, AuthEventType.LOGIN_FAILURE, "Account disabled");
            throw new BadRequestException("Account is not verified. Please check your email.");
        }

        if (!passwordEncoder.matches(password, userDetails.getPassword())) {
            bruteForceService.recordFailedAttempt(username);
            auditService.log(username, AuthEventType.LOGIN_FAILURE, "Wrong password");
            throw new BadRequestException("Invalid credentials");
        }

        bruteForceService.resetFailedAttempts(username);
        auditService.log(username, AuthEventType.LOGIN_SUCCESS, "Login successful (step 1)");

        User user = userRepository.findByEmail(username).orElse(null);
        if (user == null) {
            throw new BadRequestException("User record not found");
        }

        if (mfaService.isMfaEnabled(user.getId())) {
            String mfaToken = UUID.randomUUID().toString();
            pendingMfaTokens.put(mfaToken, new PendingMfa(user.getId(), Instant.now().plusSeconds(MFA_TOKEN_TTL_SECONDS)));
            log.debug("Issued MFA token for user={} (TTL={}s)", user.getId(), MFA_TOKEN_TTL_SECONDS);
            return new LoginResult("MFA_REQUIRED", mfaToken, user.getId(), null);
        }

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    /**
     * Step 2: Validates TOTP code for MFA-enabled users.
     *
     * @throws BadRequestException if the mfaToken is missing, expired, already
     *                             used, or does not match the userId, OR if the
     *                             TOTP code is invalid.
     */
    public LoginResult verifyMfa(UUID userId, String mfaToken, String totpCode) {
        validateMfaToken(mfaToken, userId);

        if (!mfaService.verifyTotp(userId, totpCode)) {
            // Brute-force protection on the MFA endpoint itself (OWASP MFA Cheat Sheet).
            recordMfaFailure(userId);
            throw new BadRequestException("Invalid MFA code");
        }

        consumeMfaToken(mfaToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found"));
        auditService.log(user.getEmail(), AuthEventType.LOGIN_SUCCESS, "MFA verified");

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    /**
     * Step 2 alternative: Verifies recovery code.
     *
     * @throws BadRequestException if the mfaToken is missing/expired/used/mismatched,
     *                             OR if the recovery code is invalid.
     */
    public LoginResult verifyRecoveryCode(UUID userId, String mfaToken, String recoveryCode) {
        validateMfaToken(mfaToken, userId);

        if (!mfaService.verifyRecoveryCode(userId, recoveryCode)) {
            recordMfaFailure(userId);
            throw new BadRequestException("Invalid recovery code");
        }

        consumeMfaToken(mfaToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found"));
        auditService.log(user.getEmail(), AuthEventType.LOGIN_SUCCESS, "Recovery code used");

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    /**
     * Validates the single-use MFA token against the in-memory pending store.
     * Does NOT consume the token — call {@link #consumeMfaToken(String)} only
     * after the TOTP/recovery-code verification succeeds, so that a wrong TOTP
     * does not consume the token (letting the user retry within the 5-min window).
     */
    private void validateMfaToken(String mfaToken, UUID expectedUserId) {
        if (mfaToken == null || mfaToken.isBlank()) {
            throw new BadRequestException("Missing MFA token — complete step 1 first");
        }
        PendingMfa pending = pendingMfaTokens.get(mfaToken);
        if (pending == null) {
            throw new BadRequestException("Invalid or already-used MFA token");
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            pendingMfaTokens.remove(mfaToken);
            throw new BadRequestException("MFA token expired — restart login");
        }
        if (!pending.userId().equals(expectedUserId)) {
            // Possible token-theft attempt — log and reject.
            log.warn("MFA token userId mismatch: token_user={} but request_user={}", pending.userId(), expectedUserId);
            throw new BadRequestException("MFA token does not match user");
        }
    }

    /** Removes the MFA token from the pending store — single-use enforcement. */
    private void consumeMfaToken(String mfaToken) {
        pendingMfaTokens.remove(mfaToken);
    }

    /** Records a failed MFA/recovery attempt against the user (for brute-force lockout). */
    private void recordMfaFailure(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            bruteForceService.recordFailedAttempt(user.getEmail());
            auditService.log(user.getEmail(), AuthEventType.LOGIN_FAILURE, "Failed MFA/recovery attempt");
        }
    }

    /** Pending MFA token entry. */
    private record PendingMfa(UUID userId, Instant expiresAt) {
    }

    public record LoginResult(String status, String mfaToken, UUID userId, String jwt) {
    }
}
