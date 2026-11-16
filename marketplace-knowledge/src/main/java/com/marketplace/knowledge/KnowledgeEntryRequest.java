package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4): the contribution body — the
 * {@code JobRequest}/{@code InstitutionRequest} house shape (a
 * top-level validation-carrier record the service receives directly);
 * the SAME shape carries the revision (the author's edit re-submits the
 * entry's complete new content).
 */
public record KnowledgeEntryRequest(
        @NotNull
        @Schema(description = "The geo tree node id — a level-3 neighborhood node (the neighborhood this entry documents).")
        UUID locationId,
        @NotNull
        @Schema(description = "PLACES, SERVICES, HISTORY, PEOPLE or TIPS — the guide's own vocabulary.")
        KnowledgeCategory category,
        @NotBlank @Size(max = 200)
        @Schema(description = "The entry's title.", example = "مسجد الحي: القصة والتاريخ")
        String title,
        @NotBlank @Size(max = 20000)
        @Schema(description = "The entry's body — the community-built knowledge itself.")
        String body
) {
}
