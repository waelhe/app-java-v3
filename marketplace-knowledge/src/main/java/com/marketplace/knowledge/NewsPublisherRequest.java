package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * D-3 (JT-19/D-30): the publisher registration body — the
 * {@code InstitutionRequest} house shape (a top-level validation-carrier
 * record the service receives directly). The admin is the caller; the
 * outlet is born UNVERIFIED (the honest registry — the trust mark
 * arrives only through the verification verdict).
 */
public record NewsPublisherRequest(
        @NotBlank @Size(max = 200)
        @Schema(description = "The publisher's registry name.", example = "وكالة قدسيا للأنباء")
        String name,
        @Size(max = 500)
        @Schema(description = "The optional public website of the outlet.")
        String websiteUrl
) {
}
