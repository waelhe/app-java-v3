package com.marketplace.availability;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The daily slot generator's per-rule unit of work — one rule, ONE
 * transaction (CodeRabbit round 1 on PR #471, adopted from the root:
 * {@code AvailabilityService.onDayHasPassed} used to run every rule inside
 * the listener's single transaction, so a constraint failure surfacing at
 * flush time poisoned the whole batch: the per-rule catch can absorb the
 * Java exception, but it cannot recover the aborted transaction — earlier
 * generated slots rolled back and a commit-time failure escaped the catch
 * entirely, leaving the Modulith event publication entry incomplete for
 * retry).
 *
 * <p><b>The official pattern (Spring Framework reference, Declarative
 * Transaction Management):</b> the unit runs in its own
 * {@code REQUIRES_NEW} transaction behind a SEPARATE BEAN — invoked through
 * the proxy, never self-invoked (self-invocation bypasses the proxy and the
 * propagation would silently not apply). A failure now rolls back exactly
 * that rule; the caller's catch absorbs {@link DataAccessException} and the
 * batch — and the event publication log entry — stays healthy.
 *
 * <p>R3 (comprehensive-review-ar-fix plan §4/R3): the existence probe is
 * window existence REGARDLESS of {@code booked} — a booked slot IS the
 * window; the old {@code booked = false} probe made a held row invisible and
 * produced the open duplicate riding next to it. The DB backstop underneath
 * is V73's {@code uq_availability_slots_live_window} partial unique index:
 * a racing insert past any application check gets 23505, this method's own
 * transaction rolls back, and the caller's documented per-rule catch absorbs
 * it as the best-effort outcome.
 */
@Component
public class AvailabilitySlotGenerator {

    private static final Logger log = LoggerFactory.getLogger(AvailabilitySlotGenerator.class);

    private final AvailabilitySlotRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public AvailabilitySlotGenerator(AvailabilitySlotRepository repository,
                                     ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Generates the rule's slot for the given date in a transaction of its
     * own. Skips when the window already exists — booked or open alike.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateFrom(ProviderAvailabilityRule rule, LocalDate date) {
        Instant startsAt = date.atTime(rule.getStartTime()).toInstant(ZoneOffset.UTC);
        Instant endsAt = date.atTime(rule.getEndTime()).toInstant(ZoneOffset.UTC);
        if (repository.existsByProviderIdAndStartsAtAndEndsAt(rule.getProviderId(), startsAt, endsAt)) {
            // R3: existence regardless of booked — the held row counts.
            return;
        }
        repository.save(AvailabilitySlot.open(rule.getProviderId(), startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(
                AvailabilityService.AVAILABILITY_DEPENDENT_CACHE_NAMES));
        log.info("Generated slot for provider {}: {} - {}", rule.getProviderId(), startsAt, endsAt);
    }
}
