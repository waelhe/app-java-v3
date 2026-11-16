package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one review the requester authored ("ما ألّفه بنفسه"
 * — the plan's provenance rule: his sent reviews, both directions).
 *
 * <p><b>Counterparty minimality (the plan's field-audit rule):</b> the
 * reviewed party appears as one opaque UUID in the direction's own field —
 * both fields are users.id space (the physically measured fact: V6's
 * {@code reviews.provider_id REFERENCES users(id)}, and the write path
 * stores {@code BookingInfo.providerId()} — the booking's provider user id
 * — in both directions; the entity javadoc's "profiles.id space" wording
 * is documentation drift, the physical FK is the truth):
 * <ul>
 *   <li>{@code CONSUMER_TO_PROVIDER}: {@code reviewedProviderUserId}
 *       carries the reviewed provider's user id.</li>
 *   <li>{@code PROVIDER_TO_CONSUMER}: {@code reviewedConsumerUserId}
 *       carries the reviewed consumer's user id.</li>
 * </ul>
 * The provider's {@code reply} on a forward review is deliberately absent —
 * it is the counterparty's authored content on a shared record, not the
 * requester's data. Internal system columns are excluded by the plan's
 * explicit exclusion rule.
 */
public record ReviewExportEntry(
        UUID id,
        UUID bookingId,
        String direction,
        int rating,
        String comment,
        UUID reviewedProviderUserId,
        UUID reviewedConsumerUserId,
        Instant createdAt,
        Instant updatedAt
) {
}
