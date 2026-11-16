package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * one seat the subject took on a neighborhood event — the RSVP as the
 * member's own stored fact (the attendance declaration), in the
 * account's Art. 20 export.
 *
 * <p>The seat is identifiers-and-timestamps personal data — no authored
 * text rides it (the reaction row's own reasoning: the fact IS the
 * data). The event travels as its opaque id; freed (soft-deleted) seats
 * are included — the b-5 discrimination verbatim: a freed seat is still
 * the subject's stored attendance history until the retention window
 * closes.
 */
public record CommunityEventSeatExportEntry(
        UUID id,
        UUID eventId,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}
