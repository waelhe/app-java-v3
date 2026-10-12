package com.marketplace.institutions;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the delegation
 * registry's read model — the {@code from}-factory house shape (the
 * {@link InstitutionResponse} twin). The verification state rides every
 * read (the honest registry — the trust signal visible, never hidden).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UrgentAlertSourceResponse(
        @Schema(description = "The delegated source's id.") UUID id,
        @Schema(description = "The delegating body's official display name.") String name,
        @Schema(description = "MUNICIPALITY, CIVIL_DEFENSE, UTILITIES, HEALTH_AUTHORITY, "
                + "EDUCATION_AUTHORITY or OTHER_DELEGATED.") String sourceType,
        @Schema(description = "UNVERIFIED, PENDING, VERIFIED or REJECTED — the delegation's "
                + "state visible on every read (the honest registry).") String verificationState,
        @Schema(description = "The row's creation time.") Instant createdAt,
        @Schema(description = "The row's last-update time.") Instant updatedAt
) {

    public static UrgentAlertSourceResponse from(UrgentAlertSource source) {
        return new UrgentAlertSourceResponse(
                source.getId(),
                source.getName(),
                source.getSourceType().name(),
                source.getVerificationState().name(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
