package com.marketplace.notifications.routing;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * channel vocabulary of the per-channel delivery ledger (V199
 * {@code notification_deliveries}) — the OUTBOUND channels whose
 * delivery has no inbox row to carry the standing
 * {@code notifications.source_event_id} key (V180): EMAIL, WS, PUSH.
 *
 * <p>The INBOX leg is deliberately absent: its idempotency ledger IS the
 * notifications row itself (the V180 partial unique index
 * {@code uq_notifications_source_event_once} — the standing pattern this
 * wave generalizes to the outbound channels, never duplicates).
 */
public enum NotificationDeliveryChannel {
    EMAIL,
    WS,
    PUSH
}
