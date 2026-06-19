package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password reset completion payload.
 *
 * <p>Password: min 8, max 64 chars — see
 * {@link com.marketplace.identity.PasswordValidator#MAX_PASSWORD_LENGTH}
 * (OWASP / NIST SP 800-63B §5.1.1).
 */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {
}
