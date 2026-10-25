package com.marketplace.notifications;

import java.util.Optional;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.notifications.routing.NotificationDeliveryChannel;
import com.marketplace.notifications.routing.NotificationDeliveryLedger;

/**
 * The dispute delivery's CHANNEL legs, in their own REQUIRES_NEW
 * transaction — the {@code UrgentAlertDelivery} house pattern (phase 7's
 * own root adoption of the same CodeRabbit finding class, applied to the
 * dispute paths).
 *
 * <p><b>Why the channels left the inbox row's shadow (CodeRabbit's final
 * round on the #544 slice, fixed here where the V199 per-channel ledger
 * lives):</b> the pre-fix shape committed the inbox row first and sent
 * the channels after — a send that failed left the row committed, and
 * the registry's resubmission answered {@code exists == true} with an
 * early return: the email and the WebSocket were LOST, stranded by the
 * very ledger that was supposed to make delivery exactly-once. The
 * channels now carry their own per-(event, recipient, channel) ledger
 * rows (V199, {@link NotificationDeliveryLedger}) inside this unit —
 * record, send, commit-at-end:
 * <ul>
 *   <li>a send that fails rolls the unit's ledger rows back too — the
 *       registry retry re-records and re-sends (AT-LEAST-ONCE within the
 *       failure window, the documented trade-off of the ledger
 *       idempotency);</li>
 *   <li>a delivery that succeeds commits rows and sends together —
 *       EXACTLY-ONCE across arbitrary resubmission (the first-writer-wins
 *       {@code ON CONFLICT DO NOTHING} answers "already delivered" as a
 *       returned 0 and the channel stays silent);</li>
 *   <li>the inbox row's existence strands nothing anymore — each
 *       channel's state is its own row.</li>
 * </ul></p>
 */
@Component
class DisputeChannelDelivery {

    private final NotificationPreferenceService preferences;
    private final EmailNotificationService emailNotificationService;
    private final Optional<SimpMessagingTemplate> messagingTemplate;
    private final NotificationDeliveryLedger deliveryLedger;
    private final NotificationTextSource text;

    DisputeChannelDelivery(NotificationPreferenceService preferences,
                           EmailNotificationService emailNotificationService,
                           Optional<SimpMessagingTemplate> messagingTemplate,
                           NotificationDeliveryLedger deliveryLedger,
                           NotificationTextSource text) {
        this.preferences = preferences;
        this.emailNotificationService = emailNotificationService;
        this.messagingTemplate = messagingTemplate;
        this.deliveryLedger = deliveryLedger;
        this.text = text;
    }

    /**
     * The channel unit: each admitted channel's ledger-guarded send. The
     * L22 preference gates stay where they always were (WS by default
     * with explicit opt-out; EMAIL by preference); the per-channel ledger
     * adds the exactly-once-across-retries discipline on top.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deliver(UUID recipientId, NotificationType type, String message, UUID sourceEventId) {
        if (preferences.isChannelEffectivelyEnabled(recipientId, type, NotificationChannel.EMAIL)
                && deliveryLedger.recordDelivery(sourceEventId, recipientId,
                        NotificationDeliveryChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email." + type.name() + ".subject",
                            NotificationTextSource.PLATFORM_LOCALE),
                    "email/notification",
                    java.util.Map.of("message", message));
        }
        if (preferences.isChannelEffectivelyEnabled(recipientId, type, NotificationChannel.WS)
                && deliveryLedger.recordDelivery(sourceEventId, recipientId,
                        NotificationDeliveryChannel.WS)) {
            messagingTemplate.ifPresent(template ->
                    template.convertAndSend("/topic/notifications/" + recipientId,
                            new WebSocketNotification(type.name(), message)));
        }
    }
}
