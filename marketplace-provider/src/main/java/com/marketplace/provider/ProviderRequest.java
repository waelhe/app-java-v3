package com.marketplace.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Provider profile: display name, bio and the L36 persona fields")
public record ProviderRequest(
        @Schema(description = "Public display name of the host", example = "Ahmad Al-Sayed Stays")
        @NotBlank @Size(max = 200) String displayName,
        @Schema(description = "Optional public bio", example = "Hosting coastal apartments in "
                + "Jeddah since 2019. Superhost-level response times.")
        @Size(max = 1000) String bio,
        @Schema(description = "L36 actor classification — omit for INDIVIDUAL. On update, omitting "
                + "keeps the stored classification (a required classification is never silently "
                + "reset, the catalog currency rule).", example = "INDEPENDENT_BROKER")
        ProviderActorType actorType,
        @Schema(description = "L36 optional public office name — full replacement on update: "
                + "omitting clears it (the bio contract).", example = "Qudsia Prime Estates")
        @Size(max = 200) String agencyName,
        @Schema(description = "L36 optional public brokerage license number, display only — "
                + "full replacement on update: omitting clears it.", example = "BR-2026-1149")
        @Size(max = 100) String licenseNumber
) {
}
