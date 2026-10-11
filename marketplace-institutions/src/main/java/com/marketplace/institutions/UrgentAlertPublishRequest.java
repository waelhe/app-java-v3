package com.marketplace.institutions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the publish body
 * — the {@link InstitutionRequest} house shape (a top-level
 * validation-carrier record the service receives directly).
 *
 * <p>The service's gates run BEFORE any write (the
 * {@code InstitutionService} order verbatim): the source must be
 * VERIFIED (409 — AC-20-01's deterministic eligibility: من يفوض؟ نفس
 * بوابة admin), the location resolves through the geo port (404 unknown)
 * and must be a level-3 neighborhood node (400), and the validity window
 * obeys the V83 time rule's twin ({@code validUntil} absent or strictly
 * after {@code validFrom} — 400).</p>
 */
public record UrgentAlertPublishRequest(
        @NotNull
        @Schema(description = "The delegating source's id — must be VERIFIED to publish.")
        UUID sourceId,
        @NotNull
        @Schema(description = "The geo tree node id — a level-3 neighborhood node (the alert's scope).")
        UUID locationId,
        @NotNull
        @Schema(description = "CRITICAL, SEVERE or ADVISORY — rendered as text on every surface "
                + "(CMP-46: the urgency level is text, never a popularity signal).")
        UrgentAlertLevel level,
        @NotBlank @Size(max = 200)
        @Schema(description = "The alert's headline (max 200 characters).", example = "انقطاع المياه صباح الخميس")
        String title,
        @NotBlank
        @Schema(description = "The alert's official body text.", example = "توقف ضخ المياه في الحي من 8 صباحاً حتى 2 ظهراً لأعمال صيانة مجدولة.")
        String body,
        @NotNull
        @Schema(description = "The validity window's start (ISO-8601) — the alert serves from this moment.")
        Instant validFrom,
        @Schema(description = "The validity window's end (ISO-8601) — optional (open-ended when "
                + "absent; withdrawal is then the only off-switch); strictly after validFrom "
                + "when present.")
        Instant validUntil
) {
}
