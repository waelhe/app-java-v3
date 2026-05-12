package com.marketplace.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProviderRequest(
        @NotBlank @Size(max = 200) String displayName,
        @Size(max = 1000) String bio
) {
}
