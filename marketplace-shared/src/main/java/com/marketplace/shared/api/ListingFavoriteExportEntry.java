package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * W3 (yelp-level plan §5 — G19, the review round's export leg): one
 * listing the subject saved — the favorite as the member's own stored
 * fact, in the account's Art. 20 export.
 *
 * <p>The favorite is identifiers-and-timestamps personal data — the fact
 * IS the data (the event-seat row's own class: no authored text rides
 * it). The listing travels as its opaque id; {@code savedAt} is the
 * row's own creation instant (the entity's own {@code savedAt} law);
 * withdrawn (soft-deleted) favorites are included — the b-5
 * discrimination verbatim: an unsaved listing is still the subject's
 * stored save/withdraw history until the retention window closes.
 */
public record ListingFavoriteExportEntry(
        UUID id,
        UUID listingId,
        Instant savedAt,
        Instant updatedAt,
        boolean deleted
) {
}
