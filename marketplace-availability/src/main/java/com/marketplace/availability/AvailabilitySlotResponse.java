package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for an availability slot — the public operational
 * surface of {@link AvailabilitySlot}: the bookable window, its state and
 * the concurrency {@code version} consumers need for optimistic reads.
 * Introduced so the HTTP boundary stops exposing the JPA entity (audit
 * 2026-09-25, clean-architecture finding 1; the entity stays internal to
 * the module per the Modulith module-API model).
 *
 * <p><b>CodeRabbit agent findings row 33 (CWE-200, 2026-10-25):</b> the
 * audit identity columns ({@code createdBy}/{@code updatedBy}) left this
 * contract together with the operational fields — and those columns carry
 * the account subject, which for native registrations IS the login email
 * ({@code AuditorAwareImpl} returns {@code Authentication.getName()}).
 * The shared read path {@code GET /providers/{id}/availability} serves
 * ANY authenticated reader, so an unrelated account could harvest a
 * provider's (or, after a booking-driven {@code releaseSlot}, a
 * consumer's) private contact address from every slot row. The fix is
 * the plan's own law from row 2: <b>the public REST contract carries no
 * audit identities</b> — the operational fields stay; audit history
 * remains queryable through the module's own {@code _aud} mirrors and
 * any future audit surface must be a separate endpoint with explicit
 * authorization (the finding's recommendation), not a rider on the
 * shared response. The timestamps ({@code createdAt}/{@code updatedAt})
 * are not personal data and keep their wire place.</p>
 */
@Schema(name = "AvailabilitySlot")
public record AvailabilitySlotResponse(
        UUID id,
        UUID providerId,
        Instant startsAt,
        Instant endsAt,
        boolean booked,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static AvailabilitySlotResponse from(AvailabilitySlot slot) {
        return new AvailabilitySlotResponse(
                slot.getId(),
                slot.getProviderId(),
                slot.getStartsAt(),
                slot.getEndsAt(),
                slot.isBooked(),
                slot.getVersion(),
                slot.getCreatedAt(),
                slot.getUpdatedAt());
    }
}
