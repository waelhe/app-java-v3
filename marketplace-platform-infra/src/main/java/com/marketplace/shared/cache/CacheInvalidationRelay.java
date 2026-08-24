package com.marketplace.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays {@link CacheInvalidationRequested} events to Redis Pub/Sub after
 * the transaction that published the event has committed successfully.
 *
 * <p>This decouples the Redis publish from the outcome of the business
 * transaction: if the transaction rolls back, the event is never delivered
 * and no instance evicts its caches for data that was not actually written.
 *
 * <p>Reference: Spring Framework — Transactional Events:
 * "The {@code @TransactionalEventListener} annotation exposes a phase
 * attribute that lets you customize the phase of the transaction to which
 * the listener should be bound. The valid phases are BEFORE_COMMIT,
 * AFTER_COMMIT (default), AFTER_ROLLBACK, as well as AFTER_COMPLETION [...]
 * If no transaction is running, the listener is not invoked at all, since
 * we cannot honor the required semantics."
 * https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
 *
 * <p>Reference: Spring Modulith — Events (Event Publication Registry):
 * "Spring Modulith ships with an event publication registry that hooks into
 * the core event publication mechanism of Spring Framework."
 * https://docs.spring.io/spring-modulith/reference/events.html
 */
@Component
public class CacheInvalidationRelay {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationRelay.class);

    private final CacheInvalidationPublisher publisher;

    public CacheInvalidationRelay(CacheInvalidationPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publishes the Redis invalidation message for each requested cache,
     * only after the originating transaction committed successfully.
     *
     * @param event the invalidation request carrying the cache names
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCacheInvalidationRequested(CacheInvalidationRequested event) {
        log.debug("Relaying cache invalidation after commit: {}", event.cacheNames());
        event.cacheNames().forEach(publisher::publishInvalidation);
    }
}
