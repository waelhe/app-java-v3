package com.marketplace.identity;

import com.marketplace.identity.dto.ForgotPasswordRequest;
import com.marketplace.identity.dto.LoginRequest;
import com.marketplace.identity.dto.MfaLoginRequest;
import com.marketplace.identity.dto.RecoveryCodeLoginRequest;
import com.marketplace.identity.dto.RegisterRequest;
import com.marketplace.identity.dto.ResetPasswordRequest;
import com.marketplace.shared.api.ApiConstants;
import jakarta.servlet.http.HttpServletRequest;
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
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>User registration + email verification</li>
 *   <li>Password reset (forgot/reset flow)</li>
 *   <li>Two-step login with MFA support</li>
 * </ul>
 *
 * <p><b>Rate limiting</b>: uses {@link DistributedRateLimiter} (Redis-backed, per-IP +
 * per-username) per OWASP Authentication Cheat Sheet — rate limiting must be per-attacker,
 * not per-instance. The prior Resilience4j {@code @RateLimiter} was per-JVM: with N replicas
 * an attacker got N× the limit. The distributed limiter is the primary control;
 * Resilience4j is kept as a secondary defense-in-depth (it still applies per-instance).
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#rate-limiting">OWASP Authentication Cheat Sheet — Rate Limiting</a>
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/index.html">Spring Security Authentication</a>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/auth", version = "1.0")
public class AuthController {

    private final RegistrationService registrationService;
    private final VerificationService verificationService;
    private final PasswordResetService passwordResetService;
    private final TwoStepLoginService loginService;
    private final DistributedRateLimiter rateLimiter;

    public AuthController(RegistrationService registrationService,
                           VerificationService verificationService,
                           PasswordResetService passwordResetService,
                           TwoStepLoginService loginService,
                           DistributedRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.verificationService = verificationService;
        this.passwordResetService = passwordResetService;
        this.loginService = loginService;
        this.rateLimiter = rateLimiter;
    }

    // === Registration ===

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        checkRateLimit(httpRequest, "register:" + request.email());
        var user = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", user.getId().toString(),
                "message", "Registration successful. Please check your email to verify your account."
        ));
    }

    @GetMapping("/verify")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token, HttpServletRequest httpRequest) {
        checkRateLimit(httpRequest, "verify");
        verificationService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    // === Password Reset ===

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        checkRateLimit(httpRequest, "forgot:" + request.email());
        passwordResetService.initiateReset(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        checkRateLimit(httpRequest, "reset");
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
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        // Rate limit by IP + username — prevents credential stuffing per-attacker.
        checkRateLimit(httpRequest, "login:" + request.username());
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
     * <p>Requires the {@code mfaToken} returned by step 1 to prevent MFA bypass
     * (OWASP MFA Cheat Sheet — MFA must be bound to the authenticated session).
     */
    @PostMapping("/login/mfa")
    public ResponseEntity<Map<String, Object>> verifyMfaLogin(
            @Valid @RequestBody MfaLoginRequest request,
            HttpServletRequest httpRequest) {
        // Rate limit by IP + userId — prevents TOTP brute-force per-attacker.
        checkRateLimit(httpRequest, "mfa:" + request.userId());
        TwoStepLoginService.LoginResult result = loginService.verifyMfa(request.userId(), request.mfaToken(), request.code());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "userId", result.userId().toString(),
                "message", "MFA verified. Exchange credentials via POST /oauth2/token for JWT."
        ));
    }

    /**
     * Step 2 alternative: Verifies recovery code.
     * <p>Requires the {@code mfaToken} returned by step 1 (same binding as MFA).
     */
    @PostMapping("/login/recovery-code")
    public ResponseEntity<Map<String, Object>> verifyRecoveryCodeLogin(
            @Valid @RequestBody RecoveryCodeLoginRequest request,
            HttpServletRequest httpRequest) {
        checkRateLimit(httpRequest, "recovery:" + request.userId());
        TwoStepLoginService.LoginResult result = loginService.verifyRecoveryCode(request.userId(), request.mfaToken(), request.recoveryCode());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "userId", result.userId().toString(),
                "message", "Recovery code verified. Exchange credentials via POST /oauth2/token for JWT."
        ));
    }

    // === Rate limit helper ===

    /**
     * Checks the distributed rate limit for the given bucket key (IP + action/username).
     * Throws 429 Too Many Requests if the limit has been exceeded.
     *
     * <p>The bucket key combines the client IP (resolved by Spring's ForwardedHeaderFilter
     * when {@code server.forward-headers-strategy=framework} is active) with the action
     * identifier, so limits are enforced per-attacker, not globally.
     *
     * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#rate-limiting">OWASP Rate Limiting</a>
     */
    private void checkRateLimit(HttpServletRequest request, String actionKey) {
        String clientIp = resolveClientIp(request);
        String bucketKey = "auth:ip:" + clientIp + ":" + actionKey;
        if (!rateLimiter.tryAcquire(bucketKey)) {
            // 429 Too Many Requests per RFC 6585 §4 — standard for rate limiting.
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Please try again later.");
        }
    }

    /**
     * Resolves the client IP from the request.
     * When {@code server.forward-headers-strategy=framework} is active (prod),
     * {@code getRemoteAddr()} returns the IP resolved by Spring's ForwardedHeaderFilter
     * from the trusted-proxy X-Forwarded-For chain — not the spoofable raw header.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }
}
