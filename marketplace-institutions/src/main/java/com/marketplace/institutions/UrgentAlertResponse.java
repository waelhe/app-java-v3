package com.marketplace.institutions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.marketplace.shared.api.UrgentAlertsPort;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the alert wire
 * model — the {@code from}-factory house shape (the
 * {@link InstitutionResponse} twin). Two factories over ONE shape: the
 * ADMIN surface reads the live {@link UrgentAlertService.ActiveAlert}
 * pair (the withdrawal facts ride their own presence), the PUBLIC
 * surface reads the {@link UrgentAlertsPort.UrgentAlertCard} projection
 * — the adapter's own semantics, so what the public read serves is byte
 * for byte what every consumer surface (discovery, notifications) serves
 * (JT-10: the withdrawal reflects on every surface through the same
 * seam).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UrgentAlertResponse(
        @Schema(description = "The alert's id.") UUID alertId,
        @Schema(description = "The delegating source's id.") UUID sourceId,
        @Schema(description = "The delegating body's official display name — the alert's "
                + "honest attribution.") String sourceName,
        @Schema(description = "MUNICIPALITY, CIVIL_DEFENSE, UTILITIES, HEALTH_AUTHORITY, "
                + "EDUCATION_AUTHORITY or OTHER_DELEGATED.") String sourceType,
        @Schema(description = "CRITICAL, SEVERE or ADVISORY — text, never a ranking weight "
                + "(CMP-46).") String level,
        @Schema(description = "The alert's headline.") String title,
        @Schema(description = "The alert's official body text.") String body,
        @Schema(description = "The alert's scope — a level-3 neighborhood node id.") UUID locationId,
        @Schema(description = "The validity window's start.") Instant validFrom,
        @Schema(description = "The validity window's end — null means open-ended.") Instant validUntil,
        @Schema(description = "The row's last-update time.") Instant updatedAt,
        @Schema(description = "True once withdrawn — the surfaces stop serving it (JT-10); the "
                + "public read never returns a withdrawn alert, this leg is the admin view's "
                + "honesty.") boolean withdrawn,
        @Schema(description = "The withdrawal moment — present only once withdrawn.") Instant withdrawnAt
) {

    /** The admin surface — the alert and its source as the engine resolved them. */
    public static UrgentAlertResponse from(UrgentAlertService.ActiveAlert active) {
        UrgentAlert alert = active.alert();
        UrgentAlertSource source = active.source();
        return new UrgentAlertResponse(
                alert.getId(),
                alert.getSourceId(),
                source.getName(),
                source.getSourceType().name(),
                alert.getLevel().name(),
                alert.getTitle(),
                alert.getBody(),
                alert.getLocationId(),
                alert.getValidFrom(),
                alert.getValidUntil(),
                alert.getUpdatedAt(),
                alert.isWithdrawn(),
                alert.getWithdrawnAt());
    }

    /** The public surface — the port card's projection verbatim (the adapter's semantics). */
    public static UrgentAlertResponse from(UrgentAlertsPort.UrgentAlertCard card) {
        return new UrgentAlertResponse(
                card.alertId(),
                card.sourceId(),
                card.sourceName(),
                card.sourceType(),
                card.level(),
                card.title(),
                card.body(),
                card.locationId(),
                card.validFrom(),
                card.validUntil(),
                card.updatedAt(),
                false,
                null);
    }
}
