package com.marketplace.notifications.routing;

import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the mobile
 * push channel's executor seam — the contract the D-10 provider
 * implementation will satisfy.
 *
 * <p><b>Why a seam and not a provider (the measured decision, documented):
 * </b> the plan holds the push-provider decision open — D-10 ("مزود
 * push | لم يختر") — and §8.1 defers the device tokens to AFTER that
 * decision ("رموز أجهزة بعد قرار مزود push (D-10)"). The measured repo
 * state agrees: the firebase-admin dependency is MANAGED at the root pom
 * (Exception #17) but no device-token table, no FCM sender and no push
 * configuration exist anywhere on main — the worklog's "V178 added
 * device_tokens + FCM" note does not match the applied tree (V178 is the
 * delegated-urgent-sources migration; its checksum is registered in the
 * shared manifest). So this wave lands the CONTRACT (this interface, the
 * {@code PUSH} ledger channel, the engine's push dimension) and leaves
 * the provider an {@code Optional} — absent until D-10 lands, at which
 * point one implementation bean activates the channel with zero engine
 * changes.
 *
 * <p>The WebSocket is NOT this channel (§8.1 verbatim): a WS broadcast
 * reaches a live web session's broker topic; this seam is the
 * device-targeted mobile push.
 */
public interface PushNotificationChannel {

    /**
     * Sends one push message to one recipient's registered devices.
     *
     * @param recipientId the user whose devices receive the message (the
     *                    device-token store itself is the D-10 decision's
     *                    own schema — deliberately not created here)
     * @param title       the message title (the source's honest attribution)
     * @param body        the message body
     */
    void send(UUID recipientId, String title, String body);
}
