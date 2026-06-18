package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record RecoveryCodeLoginRequest(
        @NotBlank java.util.UUID userId,
        @NotBlank String recoveryCode
) {
}
