package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A delegated urgent alert was withdrawn — the CJIS-style honesty leg of
 * the alert lifecycle: the withdrawal reflects on every surface that
 * carried the alert (the JT-10 contract: "تصحيح/سحب ينعكس على كل
 * الأسطح" — the knowledge revise/withdraw precedent, applied to
 * institutional alerts).
 *
 * <p>Consumers honor it idempotently: the notifications module keeps the
 * historical notification rows (a sent notification is a delivery fact)
 * while the ALERT SURFACES (discovery rails, the CMP-46 banner) stop
 * serving the alert immediately — the surface reads state from
 * {@link UrgentAlertsPort}, which answers withdrawn alerts with silence.</p>
 *
 * @param alertId    the withdrawn alert's id
 * @param occurredAt withdrawal moment
 */
public record UrgentAlertWithdrawnEvent(UUID alertId, Instant occurredAt) {
}
