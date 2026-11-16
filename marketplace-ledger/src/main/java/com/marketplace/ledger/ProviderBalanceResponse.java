package com.marketplace.ledger;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for a provider's ledger balance — a 1:1 snapshot of
 * {@link ProviderBalance}'s <em>measured</em> JSON surface.
 *
 * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's
 * currency):</b> the balance is per-currency — the row's composite key is
 * {@code (provider, currency)} (V75) and the read surfaces return ONE row
 * per currency the provider holds. The {@code currency} field is the
 * row's own ISO 4217 code; the money-path responses (credit / commission
 * / refund) carry the touched currency's row. The {@code id} property
 * stays the provider id (the entity's exposed id), keeping the pre-R9
 * wire identity stable while the balance itself stops mixing
 * currencies.</p>
 */
@Schema(name = "ProviderBalance")
public record ProviderBalanceResponse(
        UUID id,
        String currency,
        long availableCents,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static ProviderBalanceResponse from(ProviderBalance balance) {
        return new ProviderBalanceResponse(
                balance.getId(),
                balance.getCurrency(),
                balance.getAvailableCents(),
                balance.getVersion(),
                balance.getCreatedBy(),
                balance.getCreatedAt(),
                balance.getUpdatedBy(),
                balance.getUpdatedAt());
    }
}
