package com.marketplace.identity;

import com.marketplace.identity.dto.ForgotPasswordRequest;
import com.marketplace.identity.dto.LoginRequest;
import com.marketplace.identity.dto.MfaLoginRequest;
import com.marketplace.identity.dto.RecoveryCodeLoginRequest;
import com.marketplace.identity.dto.RegisterRequest;
import com.marketplace.identity.dto.ResetPasswordRequest;
import com.marketplace.shared.api.ApiConstants;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST Controller for authentication operations.
 * <p>Provides endpoints for:
 * <ul>
 *   <li>User registration + email verification</li>
 *   <li>Password reset (forgot/reset flow)</li>
 *   <li>Two-step login with MFA support</li>
 * </ul>
 *
 * <p>Rate-limited to prevent brute-force attacks (OWASP recommendation).
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet</a>
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/index.html">Spring Security Authentication</a>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/auth", version = "1.0")
public class AuthController {

    private final RegistrationService registrationService;
    private final VerificationService verificationService;
    private final PasswordResetService passwordResetService;
    private final TwoStepLoginService loginService;

    public AuthController(RegistrationService registrationService,
                           VerificationService verificationService,
                           PasswordResetService passwordResetService,
                           TwoStepLoginService loginService) {
        this.registrationService = registrationService;
        this.verificationService = verificationService;
        this.passwordResetService = passwordResetService;
        this.loginService = loginService;
    }

    // === Registration ===

    @PostMapping("/register")
    @RateLimiter(name = "auth")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        var user = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", user.getId().toString(),
                "message", "Registration successful. Please check your email to verify your account."
        ));
    }

    @GetMapping("/verify")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        verificationService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    // === Password Reset ===

    @PostMapping("/forgot-password")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.initiateReset(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    // === Two-Step Login (MFA + BruteForce integrated) ===

    /**
     * Step 1: Validates credentials.
     * <p>Returns:
     * <ul>
     *   <li>{@code 200} with {@code {status: "MFA_REQUIRED", mfaToken, userId}} if MFA is enabled</li>
     *   <li>{@code 200} with {@code {status: "SUCCESS", userId}} if MFA is not enabled</li>
     *   <li>{@code 400} if credentials are invalid or account is locked</li>
     * </ul>
     *
     * <p>Note: After SUCCESS, client should exchange credentials via
     * Spring Authorization Server's {@code POST /oauth2/token} to get a JWT.
     */
    @PostMapping("/login")
    @RateLimiter(name = "auth")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        TwoStepLoginService.LoginResult result = loginService.login(request.username(), request.password());

        if ("MFA_REQUIRED".equals(result.status())) {
            return ResponseEntity.ok(Map.of(
                    "status", "MFA_REQUIRED",
                    "mfaToken", result.mfaToken(),
                    "userId", result.userId().toString(),
                    "message", "MFA code required. POST /api/v1/auth/login/mfa to complete login."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "userId", result.userId().toString(),
                "message", "Login successful. Exchange credentials via POST /oauth2/token for JWT."
        ));
    }

    /**
     * Step 2: Validates TOTP code for MFA-enabled users.
     */
    @PostMapping("/login/mfa")
    @RateLimiter(name = "auth")
    public ResponseEntity<Map<String, Object>> verifyMfaLogin(@Valid @RequestBody MfaLoginRequest request) {
        TwoStepLoginService.LoginResult result = loginService.verifyMfa(request.userId(), request.code());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "userId", result.userId().toString(),
                "message", "MFA verified. Exchange credentials via POST /oauth2/token for JWT."
        ));
    }

    /**
     * Step 2 alternative: Verifies recovery code.
     */
    @PostMapping("/login/recovery-code")
    @RateLimiter(name = "auth")
    public ResponseEntity<Map<String, Object>> verifyRecoveryCodeLogin(@Valid @RequestBody RecoveryCodeLoginRequest request) {
        TwoStepLoginService.LoginResult result = loginService.verifyRecoveryCode(request.userId(), request.recoveryCode());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "userId", result.userId().toString(),
                "message", "Recovery code verified. Exchange credentials via POST /oauth2/token for JWT."
        ));
    }
}
