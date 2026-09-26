package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The provider-listing summary the administrative roster surfaces. S7
 * (platform-readiness audit §5 — the shape row): every response that carries a
 * monetary amount now carries its ISO 4217 currency too, so a client never
 * formats a bare number — {@code price} and {@code currency} travel together
 * exactly like {@link ListingResponse} and {@link ListingSummary} already do.
 * The field is additive to the record's contract (a new component, no
 * component removed or reordered before it).
 */
public record ProviderListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        String currency,
        UUID providerId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
