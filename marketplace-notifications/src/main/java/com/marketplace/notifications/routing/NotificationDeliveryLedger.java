package com.marketplace.notifications.routing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * per-channel delivery ledger — the channel dimension of the §8.1
 * idempotency identity (event id + recipient + channel).
 *
 * <p><b>The mechanism is the house's own idempotency-insert pattern
 * verbatim</b> (the V54 {@code saved_search_matches} ledger, the
 * {@code SavedSearchService} writer): a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} whose returned row count IS
 * the answer — {@code 1} means this delivery is the first (recorded,
 * proceed), {@code 0} means the (event, recipient, channel) triple was
 * already delivered (the skip is a returned 0, never a
 * transaction-aborting 23505). A retried publication — the Event
 * Publication Registry's resubmission of an incomplete entry — re-runs
 * the delivery path and lands on the skip branch: <b>a retry can never
 * duplicate an outbound delivery</b> (the Phase 7 gate "إعادة المحاولة
 * لا تضاعف الإشعارات أو التوزيع", the declared debt D-E10 closed for
 * every path that routes through the engine).
 *
 * <p>OPERATIONAL LEDGER (the V54/event_publication precedent): rows are
 * born complete and never mutated — no entity, no Envers mirror, the row
 * IS the audit trail. The write rides the caller's transaction so the
 * ledger row and the delivery it guards commit (or roll back) together.
 */
@Component
public class NotificationDeliveryLedger {

    private static final String INSERT_SQL =
            "INSERT INTO notification_deliveries (id, event_id, recipient_id, channel, delivered_at) "
                    + "VALUES (?, ?, ?, ?, now()) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public NotificationDeliveryLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records one outbound delivery attempt — atomically, first-writer-wins.
     *
     * @param eventId     the source event's id (the policy's idempotency seed)
     * @param recipientId the delivery's recipient
     * @param channel     the outbound channel (EMAIL/WS/PUSH — the inbox leg
     *                    ledgers on the notifications row itself, V180)
     * @return true when this call is the FIRST delivery of the triple (the
     *         caller must perform the send); false when the triple was
     *         already delivered (the caller must skip — the retry's no-op)
     */
    public boolean recordDelivery(UUID eventId, UUID recipientId,
                                  NotificationDeliveryChannel channel) {
        return jdbcTemplate.update(INSERT_SQL, UUID.randomUUID(), eventId, recipientId,
                channel.name()) == 1;
    }
}
