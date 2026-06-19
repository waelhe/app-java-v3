package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the recovery-code login step of the two-step login flow.
 *
 * <p>Like {@link MfaLoginRequest}, this requires the {@code mfaToken} from step 1
 * to bind the recovery-code attempt to a successful password verification.
 *
 * @param userId       the user identifier returned by step 1
 * @param mfaToken     the single-use token returned by step 1 (valid 5 minutes)
 * @param recoveryCode the 16-character recovery code (alphanumeric, uppercase)
 */
public record RecoveryCodeLoginRequest(
        @NotNull java.util.UUID userId,
        @NotBlank @Size(min = 36, max = 36) String mfaToken,
        @NotBlank @Size(min = 16, max = 16) @Pattern(regexp = "[A-Z0-9]+") String recoveryCode
) {
}
