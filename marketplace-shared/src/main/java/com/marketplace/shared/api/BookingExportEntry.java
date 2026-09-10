package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one of the requester's first-party bookings — a
 * shared record exported under the plan's provenance rule ("what the data
 * subject provided" — here, status/dates/amounts, the enumeration the plan
 * fixes verbatim).
 *
 * <p><b>Counterparty minimality (the plan's field-audit rule):</b> the other
 * party appears as an opaque {@code counterpartyId} UUID only — never a
 * name, email, or profile. The free-text {@code notes} column is
 * deliberately absent: free texts are gate b-3's declared residual (shared
 * authorship between the two parties), and the plan's booking enumeration
 * carries status/dates/amounts only. Internal system columns
 * (version/is_deleted/created_by/updated_by) are excluded by the plan's
 * explicit exclusion rule.
 *
 * @param role which side of the shared record the requester is on —
 *             {@code CONSUMER} or {@code PROVIDER}
 */
public record BookingExportEntry(
        UUID id,
        String role,
        UUID counterpartyId,
        UUID listingId,
        String status,
        Instant startsAt,
        Instant endsAt,
        Long priceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}
