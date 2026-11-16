package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The REST contract for a provider's weekly availability rule — the
 * public operational surface (the recurring window the slot generator
 * expands). Introduced so the HTTP boundary stops exposing the JPA
 * entity (audit 2026-09-25, clean-architecture finding 1);
 * {@code @Schema(name)} pins the OpenAPI schema name so the published
 * contract keeps its identity.
 *
 * <p><b>CodeRabbit agent findings row 33 (CWE-200, 2026-10-25):</b> the
 * audit identity columns ({@code createdBy}/{@code updatedBy}) carry the
 * account subject — the login email for native registrations — and have
 * no place in a shared REST contract (the same law
 * {@code AvailabilitySlotResponse} now documents). The operational
 * fields and the audit <em>timestamps</em> stay; identities remain
 * internal to the module's {@code _aud} mirrors.</p>
 */
@Schema(name = "ProviderAvailabilityRule")
public record ProviderAvailabilityRuleResponse(
        UUID id,
        UUID providerId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static ProviderAvailabilityRuleResponse from(ProviderAvailabilityRule rule) {
        return new ProviderAvailabilityRuleResponse(
                rule.getId(),
                rule.getProviderId(),
                rule.getDayOfWeek(),
                rule.getStartTime(),
                rule.getEndTime(),
                rule.getVersion(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }
}
