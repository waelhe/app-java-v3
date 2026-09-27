package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The REST contract for a provider's weekly availability rule — a 1:1
 * snapshot of {@link ProviderAvailabilityRule}'s JSON surface (entity fields
 * plus the audit columns inherited from the shared {@code BaseEntity}).
 * Introduced so the HTTP boundary stops exposing the JPA entity (audit
 * 2026-09-25, clean-architecture finding 1); {@code @Schema(name)} pins the
 * OpenAPI schema name so the published contract is unchanged.
 */
@Schema(name = "ProviderAvailabilityRule")
public record ProviderAvailabilityRuleResponse(
        UUID id,
        UUID providerId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static ProviderAvailabilityRuleResponse from(ProviderAvailabilityRule rule) {
        return new ProviderAvailabilityRuleResponse(
                rule.getId(),
                rule.getProviderId(),
                rule.getDayOfWeek(),
                rule.getStartTime(),
                rule.getEndTime(),
                rule.getVersion(),
                rule.getCreatedBy(),
                rule.getCreatedAt(),
                rule.getUpdatedBy(),
                rule.getUpdatedAt());
    }
}
