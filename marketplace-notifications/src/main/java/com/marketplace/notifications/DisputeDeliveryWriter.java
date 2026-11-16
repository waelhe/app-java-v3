package com.marketplace.notifications;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispute delivery's write unit, in its own REQUIRES_NEW transaction —
 * the {@code MessageSendWriter} house pattern (B-04's CodeRabbit root
 * adoption) applied to the delivery-dedup ledger (CodeRabbit round on the
 * #540 slice).
 *
 * <p><b>Why a separate transaction for the INSERT:</b> the dedup contract
 * promises that a concurrent duplicate loses the race QUIETLY — the row
 * exists, the delivery happened, no listener fails, no registry entry
 * poisons. In PostgreSQL the transaction that takes the unique violation
 * is aborted: no statement can run inside it anymore, and the surrounding
 * {@code REQUIRED} transaction is marked rollback-only the moment the
 * violation crosses a proxy boundary inside it. Catching the exception
 * inside that outer transaction can therefore never produce a successful
 * commit — the absorb has to happen OUTSIDE the transaction that took the
 * violation. Running the INSERT here, in its own transaction, keeps the
 * listener-facing method's context healthy: the inner transaction rolls
 * back alone, the catch answers the duplicate with a quiet log line, and
 * the amplification channels (email/WebSocket) are skipped exactly as
 * the winner's row implies — they were already sent by the winner.
 */
@Component
class DisputeDeliveryWriter {

    private final NotificationRepository notificationRepository;

    DisputeDeliveryWriter(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * The ledger INSERT itself — flushed inside the method so the unique
     * violation surfaces HERE, on THIS transaction, not at some later
     * commit where no catch could answer it with the quiet duplicate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Notification persist(UUID recipientId, String type, String message, UUID sourceEventId) {
        return notificationRepository.saveAndFlush(
                Notification.create(recipientId, type, message, sourceEventId));
    }
}
