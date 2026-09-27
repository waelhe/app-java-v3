package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for a provider's time-off window — a 1:1 snapshot of
 * {@link ProviderTimeOff}'s <em>measured</em> JSON surface. The entity
 * exposes no getters for {@code providerId}/{@code startsAt}/{@code endsAt}
 * (JPA field access; Jackson's default getter detection), so today's wire
 * shape — confirmed against the live production {@code /v3/api-docs)} — is
 * exactly: {@code id} plus the audit columns inherited from the shared
 * {@code BaseEntity}. This record mirrors that surface precisely; no field
 * is added and none dropped. Introduced so the HTTP boundary stops exposing
 * the JPA entity (audit 2026-09-25, clean-architecture finding 1);
 * {@code @Schema(name)} keeps the OpenAPI schema name stable.
 */
@Schema(name = "ProviderTimeOff")
public record ProviderTimeOffResponse(
        UUID id,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static ProviderTimeOffResponse from(ProviderTimeOff timeOff) {
        return new ProviderTimeOffResponse(
                timeOff.getId(),
                timeOff.getVersion(),
                timeOff.getCreatedBy(),
                timeOff.getCreatedAt(),
                timeOff.getUpdatedBy(),
                timeOff.getUpdatedAt());
    }
}
