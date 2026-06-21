package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the MFA verification step of the two-step login flow.
 *
 * <p>The {@code mfaToken} binds this step to a successful step-1 password
 * verification. Without it, an attacker who only knows the user's UUID could
 * skip the password and brute-force the 6-digit TOTP directly — a critical
 * MFA bypass (OWASP MFA Cheat Sheet: "MFA must be bound to the authenticated session").
 *
 * @param userId   the user identifier returned by step 1
 * @param mfaToken the single-use token returned by step 1 (valid 5 minutes)
 * @param code     the 6-digit TOTP code from the user's authenticator app
 */
public record MfaLoginRequest(
        @NotNull java.util.UUID userId,
        @NotBlank @Size(min = 36, max = 36) String mfaToken,
        @NotBlank @Size(min = 6, max = 6) String code
) {
}
