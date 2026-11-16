package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one conversation the requester participates in — a
 * shared record exported under the plan's counterparty-minimality rule:
 * the other participant appears as an opaque {@code counterpartyUserId}
 * UUID only (never a name, email, or profile).
 */
public record ConversationExportEntry(
        UUID id,
        UUID counterpartyUserId,
        UUID bookingId,
        Instant createdAt,
        Instant updatedAt
) {
}
