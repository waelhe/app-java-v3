package com.marketplace.institutions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the delegation
 * body — the {@link InstitutionRequest} house shape (a top-level
 * validation-carrier record the service receives directly). The
 * administrative gate decides WHO calls it (the
 * {@code UrgentAlertAdminController}'s class-level
 * {@code hasRole('ADMIN')} — an official body cannot self-declare its
 * own authority); the record carries only the delegation's own facts.
 */
public record UrgentAlertSourceRequest(
        @NotBlank @Size(max = 200)
        @Schema(description = "The delegating body's official display name.", example = "أمانة محافظة الرياض")
        String name,
        @NotNull
        @Schema(description = "MUNICIPALITY, CIVIL_DEFENSE, UTILITIES, HEALTH_AUTHORITY, "
                + "EDUCATION_AUTHORITY or OTHER_DELEGATED.")
        UrgentAlertSourceType sourceType
) {
}
