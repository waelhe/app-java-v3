package com.marketplace.notifications.routing;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.notifications.EmailNotificationService;
import com.marketplace.notifications.NotificationRepository;
import com.marketplace.notifications.NotificationTextSource;
import com.marketplace.notifications.NotificationType;

/**
 * ONE recipient's urgent-alert delivery, in its own REQUIRES_NEW
 * transaction — the {@code FollowWriter}/{@code MessageSendWriter} house
 * pattern applied to the fan-out's per-recipient unit (CodeRabbit's round
 * on the #545 slice).
 *
 * <p><b>Why an independent commit boundary per recipient:</b> the fan-out
 * loops over the whole scope's candidates; with one shared transaction, a
 * failure at a LATER recipient would roll back every EARLIER recipient's
 * inbox rows and channel-ledger records — while their emails and
 * WebSocket pushes had already gone out externally (a send is not
 * rollback-able). The retry would then re-send everything the ledger had
 * (rolled-back) forgotten — the worst of both semantics at once. The
 * independent boundary isolates a failure to its OWN recipient: every
 * earlier recipient's ledger records are COMMITTED before the next one
 * begins, so the retry answers only what it must.
 *
 * <p><b>The per-channel failure semantics, stated once (the round's own
 * requirement):</b> <ul>
 *   <li><b>Within one recipient:</b> the inbox insert and the channel
 *       ledger writes commit at this method's end; a channel send that
 *       succeeded before a later write fails answers AT-LEAST-ONCE on the
 *       registry's resubmission — the documented trade-off of the §8.1
 *       ledger idempotency (V199's own header: "a retried publication
 *       takes the skip branch and the retry stays exactly-once per
 *       channel" — the skip rides the ledger row, which in this window
 *       had not yet committed).</li>
 *   <li><b>Across retries:</b> the per-(event, recipient, channel) ledger
 *       key makes each channel EXACTLY-ONCE per delivery cycle — the
 *       resubmission re-runs this unit, the ledger's
 *       {@code ON CONFLICT DO NOTHING} first-writer-wins answers "already
 *       delivered" as a returned 0, and no channel re-sends.</li>
 * </ul></p>
 */
@Component
class UrgentAlertDelivery {

    private final NotificationRepository repository;
    private final NotificationDeliveryLedger deliveryLedger;
    private final EmailNotificationService emailNotificationService;
    private final UrgentAlertWebSocketPush webSocketPush;
    private final Optional<PushNotificationChannel> pushChannel;
    private final NotificationTextSource text;

    UrgentAlertDelivery(NotificationRepository repository,
                        NotificationDeliveryLedger deliveryLedger,
                        EmailNotificationService emailNotificationService,
                        UrgentAlertWebSocketPush webSocketPush,
                        Optional<PushNotificationChannel> pushChannel,
                        NotificationTextSource text) {
        this.repository = repository;
        this.deliveryLedger = deliveryLedger;
        this.emailNotificationService = emailNotificationService;
        this.webSocketPush = webSocketPush;
        this.pushChannel = pushChannel;
        this.text = text;
    }

    /**
     * The delivery unit: the inbox row (the V180 ledger key — the alert's
     * own id as the source event id) via the idempotent
     * {@code ON CONFLICT DO NOTHING} insert — the V54/V199 shape, never a
     * transaction-aborting 23505 (CodeRabbit's round on the #545 slice:
     * the pre-check/flush/catch dance retired; the conflict is absorbed
     * inside the database, the returned count IS the duplicate answer) —
     * then each admitted channel's ledger-guarded send.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deliver(UUID alertId, UUID recipient, RoutingDecision decision,
                 String message, String sourceName, String title) {
        boolean firstDelivery = repository.insertOnce(UUID.randomUUID(), recipient,
                NotificationType.URGENT_ALERT.name(), message, alertId) == 1;
        if (!firstDelivery) {
            // The winner's proof: the concurrent insert lost nothing — the row
            // exists, the delivery happened, the fan-out continues.
            // (logged at the caller's debug level by the router's own story)
        }
        if (decision.email()
                && deliveryLedger.recordDelivery(alertId, recipient,
                        NotificationDeliveryChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipient,
                    text.compose("email.URGENT_ALERT.subject",
                            NotificationTextSource.PLATFORM_LOCALE, sourceName),
                    "email/notification",
                    Map.of("message", message));
        }
        if (decision.webSocket()
                && deliveryLedger.recordDelivery(alertId, recipient,
                        NotificationDeliveryChannel.WS)) {
            webSocketPush.push(recipient, NotificationType.URGENT_ALERT, message);
        }
        if (decision.push()
                && deliveryLedger.recordDelivery(alertId, recipient,
                        NotificationDeliveryChannel.PUSH)) {
            pushChannel.ifPresent(channel -> channel.send(recipient, sourceName, title));
        }
    }
}
