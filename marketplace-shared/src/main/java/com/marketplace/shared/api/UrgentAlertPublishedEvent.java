package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A delegated urgent alert went live — published by marketplace-institutions
 * when an authorized source's alert becomes active (the Modulith
 * events discipline: the publishing module owns the contract type here in
 * shared-api, consumers in notifications/discovery never depend on the
 * institutions package — the exact contract placement the execution plan
 * §1.4 mandates: "الأحداث عبر حدود Modulith فقط" with the SPI contract in
 * shared).
 *
 * <p>Consumers must be idempotent: the notifications listener dedupes on
 * {@code alertId} (the notifications.source_event_id ledger, the
 * V93/provider_follow_alerts precedent — one alert row per recipient per
 * alert, re-delivery can never duplicate).</p>
 *
 * @param alertId     the urgent alert's id
 * @param sourceId    the delegating source's id (delegated_urgent_sources)
 * @param sourceName  the source's display name (the notification's honest
 *                    attribution — the alert's authority is the SOURCE's)
 * @param locationId  the scope neighborhood (geo_locations, level-3)
 * @param level       the urgency level as stored (text, CMP-46: level is
 *                    rendered as text, never a popularity signal)
 * @param title       the alert title
 * @param occurredAt  publication moment
 */
public record UrgentAlertPublishedEvent(
        UUID alertId,
        UUID sourceId,
        String sourceName,
        UUID locationId,
        String level,
        String title,
        Instant occurredAt) {
}
