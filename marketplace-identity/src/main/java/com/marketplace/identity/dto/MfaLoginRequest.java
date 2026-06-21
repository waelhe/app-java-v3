package com.marketplace.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaLoginRequest(
        @NotBlank java.util.UUID userId,
        @NotBlank @Size(min = 6, max = 6) String code
) {
}
