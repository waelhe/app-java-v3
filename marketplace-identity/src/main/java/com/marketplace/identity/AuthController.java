package com.marketplace.identity;

import com.marketplace.identity.dto.ForgotPasswordRequest;
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
 *   <li>User registration</li>
 *   <li>Email verification</li>
 *   <li>Password reset (forgot/reset flow)</li>
 * </ul>
 *
 * <p>Rate-limited to prevent brute-force attacks (OWASP recommendation).
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet</a>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/auth", version = "1.0")
public class AuthController {

    private final RegistrationService registrationService;
    private final VerificationService verificationService;
    private final PasswordResetService passwordResetService;

    public AuthController(RegistrationService registrationService,
                           VerificationService verificationService,
                           PasswordResetService passwordResetService) {
        this.registrationService = registrationService;
        this.verificationService = verificationService;
        this.passwordResetService = passwordResetService;
    }

    /**
     * Registers a new user account.
     * Account is disabled until email verification.
     */
    @PostMapping("/register")
    @RateLimiter(name = "auth")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        var user = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", user.getId().toString(),
                "message", "Registration successful. Please check your email to verify your account."
        ));
    }

    /**
     * Verifies a user's email address using the token sent via email.
     */
    @GetMapping("/verify")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        verificationService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    /**
     * Initiates password reset flow.
     * Returns 204 silently even if email doesn't exist (OWASP recommendation).
     */
    @PostMapping("/forgot-password")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.initiateReset(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * Resets password using a valid reset token.
     */
    @PostMapping("/reset-password")
    @RateLimiter(name = "auth")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }
}
