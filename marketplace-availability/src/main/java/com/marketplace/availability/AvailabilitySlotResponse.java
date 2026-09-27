package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for an availability slot — a 1:1 snapshot of
 * {@link AvailabilitySlot}'s JSON surface (entity fields plus the audit
 * columns inherited from the shared {@code BaseEntity}: version, createdBy,
 * createdAt, updatedBy, updatedAt). Introduced so the HTTP boundary stops
 * exposing the JPA entity itself (audit 2026-09-25, clean-architecture
 * finding 1; the entity stays internal to the module per the Modulith
 * module-API model).
 *
 * <p>Field-for-field wire compatibility is deliberate: the component set
 * mirrors the entity's getter surface exactly, and {@code @Schema(name)}
 * pins the OpenAPI schema name to the entity's historical name so the
 * published contract — measured live from the production {@code /v3/api-docs}
 * — is byte-for-byte unchanged.
 */
@Schema(name = "AvailabilitySlot")
public record AvailabilitySlotResponse(
        UUID id,
        UUID providerId,
        Instant startsAt,
        Instant endsAt,
        boolean booked,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static AvailabilitySlotResponse from(AvailabilitySlot slot) {
        return new AvailabilitySlotResponse(
                slot.getId(),
                slot.getProviderId(),
                slot.getStartsAt(),
                slot.getEndsAt(),
                slot.isBooked(),
                slot.getVersion(),
                slot.getCreatedBy(),
                slot.getCreatedAt(),
                slot.getUpdatedBy(),
                slot.getUpdatedAt());
    }
}
