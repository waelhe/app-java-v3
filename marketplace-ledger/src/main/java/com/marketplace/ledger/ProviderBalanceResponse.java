package com.marketplace.ledger;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for a provider's ledger balance — a 1:1 snapshot of
 * {@link ProviderBalance}'s <em>measured</em> JSON surface. The entity's
 * {@code getId()} returns {@code providerId} (its {@code @Id}) and it
 * exposes no separate {@code getProviderId()}, so today's wire shape —
 * confirmed against the live production {@code /v3/api-docs} — carries a
 * single {@code id} property (the provider id) alongside
 * {@code availableCents} and the audit columns inherited from the shared
 * {@code BaseEntity}. This record mirrors that surface precisely;
 * {@code @Schema(name)} keeps the OpenAPI schema name stable.
 */
@Schema(name = "ProviderBalance")
public record ProviderBalanceResponse(
        UUID id,
        long availableCents,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static ProviderBalanceResponse from(ProviderBalance balance) {
        return new ProviderBalanceResponse(
                balance.getId(),
                balance.getAvailableCents(),
                balance.getVersion(),
                balance.getCreatedBy(),
                balance.getCreatedAt(),
                balance.getUpdatedBy(),
                balance.getUpdatedAt());
    }
}
