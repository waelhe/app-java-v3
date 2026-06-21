package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Two-step login flow with MFA and brute force protection integrated.
 * <p>Follows Spring Security AuthenticationManager pattern:
 * <ul>
 *   <li>Step 1: POST /api/v1/auth/login → validate credentials → return mfa_required</li>
 *   <li>Step 2: POST /api/v1/auth/login/mfa → validate TOTP → return JWT or pre-authenticated token</li>
 * </ul>
 *
 * <p>Brute force protection is integrated:
 * <ul>
 *   <li>Checks isLocked() before attempting login</li>
 *   <li>Records failed attempts on authentication failure</li>
 *   <li>Resets failed attempts on successful authentication</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/index.html">Spring Security Authentication</a>
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication</a>
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA</a>
 */
@Service
@Transactional
public class TwoStepLoginService {

    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final BruteForceProtectionService bruteForceService;
    private final MfaService mfaService;
    private final AuthAuditService auditService;
    private final UserRepository userRepository;

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
        auditService.log(username, AuthEventType.LOGIN_SUCCESS, "Login successful");

        User user = userRepository.findByEmail(username).orElse(null);
        if (user == null) {
            throw new BadRequestException("User record not found");
        }

        if (mfaService.isMfaEnabled(user.getId())) {
            String mfaToken = java.util.UUID.randomUUID().toString();
            return new LoginResult("MFA_REQUIRED", mfaToken, user.getId(), null);
        }

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    /**
     * Step 2: Validates TOTP code for MFA-enabled users.
     */
    public LoginResult verifyMfa(UUID userId, String totpCode) {
        if (!mfaService.verifyTotp(userId, totpCode)) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BadRequestException("User not found"));
            auditService.log(user.getEmail(), AuthEventType.LOGIN_FAILURE, "Invalid MFA code");
            throw new BadRequestException("Invalid MFA code");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found"));
        auditService.log(user.getEmail(), AuthEventType.LOGIN_SUCCESS, "MFA verified");

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    /**
     * Step 2 alternative: Verifies recovery code.
     */
    public LoginResult verifyRecoveryCode(UUID userId, String recoveryCode) {
        if (!mfaService.verifyRecoveryCode(userId, recoveryCode)) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BadRequestException("User not found"));
            auditService.log(user.getEmail(), AuthEventType.LOGIN_FAILURE, "Invalid recovery code");
            throw new BadRequestException("Invalid recovery code");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User not found"));
        auditService.log(user.getEmail(), AuthEventType.LOGIN_SUCCESS, "Recovery code used");

        return new LoginResult("SUCCESS", null, user.getId(), null);
    }

    public record LoginResult(String status, String mfaToken, UUID userId, String jwt) {
    }
}
