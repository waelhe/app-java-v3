package com.marketplace.identity;

import com.marketplace.identity.dto.MfaVerifyRequest;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST Controller for MFA (Multi-Factor Authentication) management.
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Setup TOTP (returns QR code URI)</li>
 *   <li>Verify and enable MFA (returns recovery codes)</li>
 *   <li>Disable MFA</li>
 *   <li>Check MFA status</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238</a>
 */
@RestController
@RequestMapping("/api/v1/users/me/mfa")
@PreAuthorize("isAuthenticated()")
public class MfaController {

    private final MfaService mfaService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final org.springframework.security.provisioning.UserDetailsManager userDetailsManager;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public MfaController(MfaService mfaService,
                          UserService userService,
                          CurrentUserProvider currentUserProvider,
                          org.springframework.security.provisioning.UserDetailsManager userDetailsManager,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.mfaService = mfaService;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Initiates MFA setup. Returns TOTP secret + otpauth URI for QR code.
     */
    @PostMapping("/setup")
    public ResponseEntity<Map<String, String>> setupMfa(Authentication auth) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);
        MfaService.MfaSetupResponse response = mfaService.setupMfa(userId, user.getEmail());
        return ResponseEntity.ok(Map.of(
                "secret", response.secret(),
                "otpAuthUri", response.otpAuthUri()
        ));
    }

    /**
     * Verifies TOTP code and enables MFA.
     * Returns recovery codes (shown once, then must be saved by user).
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyAndEnableMfa(
            @Valid @RequestBody MfaVerifyRequest request,
            Authentication auth) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);
        java.util.List<String> recoveryCodes = mfaService.verifyAndEnableMfa(
                userId, request.code(), user.getEmail());
        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "recoveryCodes", recoveryCodes,
                "message", "Save these recovery codes securely. They will not be shown again."
        ));
    }

    /**
     * Disables MFA.
     */
    @DeleteMapping
    public ResponseEntity<Void> disableMfa(
            @org.springframework.web.bind.annotation.RequestBody DisableMfaRequest request,
            Authentication auth) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);

        // OWASP MFA Cheat Sheet: verify password before MFA changes
        org.springframework.security.core.userdetails.UserDetails userDetails =
                userDetailsManager.loadUserByUsername(user.getEmail());
        if (!passwordEncoder.matches(request.password(), userDetails.getPassword())) {
            throw new com.marketplace.shared.api.BadRequestException("Current password is incorrect");
        }

        mfaService.disableMfa(userId, user.getEmail());
        return ResponseEntity.noContent().build();
    }

    public record DisableMfaRequest(@jakarta.validation.constraints.NotBlank String password) {
    }

    /**
     * Checks if MFA is enabled for current user.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getMfaStatus(Authentication auth) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(auth);
        boolean enabled = mfaService.isMfaEnabled(userId);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }
}
