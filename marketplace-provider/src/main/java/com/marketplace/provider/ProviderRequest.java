package com.marketplace.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Provider profile: display name and bio")
public record ProviderRequest(
        @Schema(description = "Public display name of the host", example = "Ahmad Al-Sayed Stays")
        @NotBlank @Size(max = 200) String displayName,
        @Schema(description = "Optional public bio", example = "Hosting coastal apartments in "
                + "Jeddah since 2019. Superhost-level response times.")
        @Size(max = 1000) String bio
) {
}
