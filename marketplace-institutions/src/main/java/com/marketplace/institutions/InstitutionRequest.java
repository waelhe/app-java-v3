package com.marketplace.institutions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * B-13 (compliance plan C.3): the registration body — the
 * {@code JobRequest}/{@code LeadRequest} house shape (a top-level
 * validation-carrier record the service receives directly).
 */
public record InstitutionRequest(
        @NotBlank @Size(max = 200)
        @Schema(description = "The institution's registry name.", example = "مدرسة النور الأهلية")
        String name,
        @NotNull
        @Schema(description = "SCHOOL, UNIVERSITY, CLINIC, MOSQUE, CHARITY, GOVERNMENT, COMPANY or NGO.")
        InstitutionType type,
        @NotNull
        @Schema(description = "The geo tree node id — a level-3 neighborhood node (the same single administrative hierarchy the membership anchor rides).")
        UUID locationId,
        @Size(max = 300)
        @Schema(description = "The optional free-text street address.")
        String address,
        @Size(max = 20)
        @Schema(description = "The optional public contact phone.")
        String phone,
        @Size(max = 300)
        @Schema(description = "The optional public website (rides the JSON-LD url only when present).")
        String website,
        @Size(max = 4000)
        @Schema(description = "The optional description (rides the JSON-LD description only when present).")
        String description
) {
}
