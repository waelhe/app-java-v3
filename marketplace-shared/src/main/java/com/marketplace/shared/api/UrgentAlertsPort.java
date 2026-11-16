package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The institutions module's delegated-urgent-alert source — the CMP-46 /
 * JT-10 surface: official alerts whose authority comes from a DELEGATED
 * source, scoped to a neighborhood, valid for a window — never ranked by
 * popularity, never granted authority by a community post or an AI label
 * (AC-20-06: an urgent tag does not make community content official).
 *
 * <p>House pattern ({@code PostLookupPort} verbatim): interface in
 * shared-api, marketplace-institutions implements it, the discovery and
 * notifications modules inject it. Only alerts whose validity window
 * covers {@code now} AND that were not withdrawn answer here.</p>
 */
public interface UrgentAlertsPort {

    /**
     * @param locationId the caller's level-3 neighborhood (the alert's
     *        scope is enforced by the adapter — an alert scoped to another
     *        neighborhood never leaks)
     * @param now the validity anchor
     */
    List<UrgentAlertCard> findActive(UUID locationId, Instant now);

    /** One delegated urgent alert as the projection speaks it. */
    record UrgentAlertCard(
            UUID alertId,
            UUID sourceId,
            String sourceName,
            String sourceType,
            String level,
            String title,
            String body,
            UUID locationId,
            Instant validFrom,
            Instant validUntil,
            Instant updatedAt) {
    }
}
