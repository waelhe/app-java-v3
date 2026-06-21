package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password change payload.
 *
 * <p>Both {@code oldPassword} and {@code newPassword} are capped at 64 chars — see
 * {@link com.marketplace.identity.PasswordValidator#MAX_PASSWORD_LENGTH}
 * (OWASP / NIST SP 800-63B §5.1.1). The previous version had no cap on
 * {@code oldPassword}, which exposed a CPU-burn DoS vector against
 * {@code passwordEncoder.matches()}.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 64) String oldPassword,
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {
}
