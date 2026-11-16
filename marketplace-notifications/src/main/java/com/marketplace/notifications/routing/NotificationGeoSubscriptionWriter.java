package com.marketplace.notifications.routing;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The geo-subscription create's write unit, in its own REQUIRES_NEW
 * transaction — the {@code FollowWriter} house pattern (the #540 slice's
 * CodeRabbit root adoption of the {@code MessageSendWriter} discipline),
 * applied to the subscription insert (CodeRabbit's round on the #545
 * slice).
 *
 * <p><b>Why a separate transaction for the INSERT:</b> the endpoint's
 * documented idempotent contract promises that a replay — including the
 * in-flight race where two concurrent same-(user, location) requests both
 * miss the lookup — answers the STANDING subscription, never an error.
 * The loser of that race learns about it from a
 * {@code DataIntegrityViolationException} at flush, and in PostgreSQL the
 * transaction that took the violation is aborted (rollback-only): no
 * statement can run inside it anymore, so neither the catch nor the
 * re-read can live in the caller's {@code REQUIRED} transaction. Running
 * the INSERT here, in its own transaction, keeps the caller's context
 * healthy: the inner transaction alone rolls back, the catch re-reads the
 * winner's committed row in a fresh transaction ({@link #replay}), and
 * the API answers the promised 200-with-existing-subscription — never a
 * 500-flavored surprise for a legitimate concurrent retry.
 */
@Component
class NotificationGeoSubscriptionWriter {

    private final NotificationGeoSubscriptionRepository repository;

    NotificationGeoSubscriptionWriter(NotificationGeoSubscriptionRepository repository) {
        this.repository = repository;
    }

    /**
     * The INSERT itself — flushed inside the method so the unique
     * violation surfaces HERE (on this transaction), not at the caller's
     * commit where no catch could answer it with the standing row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    NotificationGeoSubscription persist(NotificationGeoSubscription subscription) {
        return repository.saveAndFlush(subscription);
    }

    /**
     * The race loser's re-read, in a fresh transaction: by the time the
     * loser sees the unique violation, the winner's row is committed
     * (PostgreSQL's unique-conflict semantics — an in-flight conflicting
     * INSERT makes the loser WAIT until the winner commits or aborts), so
     * this read sees it. Same (user, location) scope as the lookup and
     * the {@code uq_notification_geo_subscriptions_live} constraint
     * (V198).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    NotificationGeoSubscription replay(UUID userId, UUID locationId) {
        return repository.findByUserIdAndLocationId(userId, locationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Live subscription vanished between the race and the replay: "
                                + userId + " / " + locationId));
    }
}
