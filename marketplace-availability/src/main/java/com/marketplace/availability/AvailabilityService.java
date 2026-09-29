package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final AvailabilitySlotRepository repository;
    private final ProviderAvailabilityRuleRepository ruleRepository;
    private final ProviderTimeOffRepository timeOffRepository;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * CodeRabbit round 1 on PR #471 (adopted from the root): the per-rule
     * generation unit lives in its own bean behind a proxied
     * {@code REQUIRES_NEW} transaction — a flush-time constraint failure
     * rolls back exactly one rule instead of poisoning the listener's whole
     * transaction (the rollback-only trap the catch cannot recover from).
     */
    private final AvailabilitySlotGenerator slotGenerator;

    private static final int SLOT_GENERATION_DAYS_AHEAD = 7;

    /**
     * Caches whose content depends on availability state. L27: the
     * window-filtered {@code search-results-v4} pages (SearchService) now
     * derive from slots and time-offs, so every availability write evicts
     * them through the existing AFTER_COMMIT relay — the same freshness
     * contract listing writes already follow for
     * {@code CatalogService.CATALOG_CACHE_NAMES}. The name is shared with
     * the catalog's invalidation set and the yml {@code spring.cache.cache-names}
     * list (pinned by {@code ListingSummaryCacheContractFilesTest}).
     * Package-private: {@link AvailabilitySlotGenerator} publishes the same
     * set for the daily generation writes.
     */
    static final Set<String> AVAILABILITY_DEPENDENT_CACHE_NAMES =
            Set.of("availability", "search-results-v4");

    public AvailabilityService(AvailabilitySlotRepository repository,
                               ProviderAvailabilityRuleRepository ruleRepository,
                               ProviderTimeOffRepository timeOffRepository,
                               ApplicationEventPublisher eventPublisher,
                               AvailabilitySlotGenerator slotGenerator) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
        this.eventPublisher = eventPublisher;
        this.slotGenerator = slotGenerator;
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public AvailabilitySlotResponse createSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        AvailabilitySlot saved = repository.save(AvailabilitySlot.open(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
        return AvailabilitySlotResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlotResponse> getSlots(UUID providerId, Instant from, Instant to) {
        return repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to)
                .stream().map(AvailabilitySlotResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("availability")
    public boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt) {
        boolean slotAvailable = repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        boolean hasTimeOffConflict = timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        return slotAvailable && !hasTimeOffConflict;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasExactAvailableSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        // Same lookup as bookSlot (findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse)
        // so create() rejects a window that confirm() could never book — the
        // sub-window case passes isAvailable (overlap) but fails here (exact).
        return repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .isPresent();
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public ProviderAvailabilityRuleResponse createRule(UUID providerId, java.time.DayOfWeek dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime) {
        return ProviderAvailabilityRuleResponse.from(
                ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime)));
    }

    @ApplicationModuleListener
    public void onDayHasPassed(DayHasPassed event) {
        LocalDate date = event.getDate();
        for (int i = 0; i < SLOT_GENERATION_DAYS_AHEAD; i++) {
            generateSlotsForDate(date.plusDays(i));
        }
    }

    /**
     * Generates availability slots for a given date based on configured rules.
     *
     * <p><b>CodeRabbit round 1 on PR #471 (adopted from the root — verified
     * against the code before action):</b> the per-rule unit now runs through
     * {@link AvailabilitySlotGenerator#generateFrom} — a SEPARATE bean behind
     * a proxied {@code REQUIRES_NEW} transaction. The old loop kept every
     * rule inside this listener's single transaction: {@code save()} queues
     * the INSERT, Hibernate may flush it before a later existence query or
     * at commit, and a flush-time 23505 (V73's backstop) leaves the
     * transaction rollback-only — the per-rule catch absorbs the Java
     * exception but cannot recover the transaction, so earlier generated
     * slots rolled back and a commit-time failure escaped the catch
     * entirely, leaving the Modulith event publication entry incomplete for
     * retry. One rule, one transaction: a duplicate-window failure now rolls
     * back exactly that rule and the batch stays healthy.
     *
     * <p><b>Exception handling policy (Spring Modulith event publication
     * log):</b> the catch stays narrowed to {@link DataAccessException} — the
     * best-effort per-rule behavior (a single bad rule does not abort the
     * whole batch) while programming errors propagate to the event
     * publication log for retry. Spring Modulith Reference ("The Event
     * Publication Registry"):
     * <blockquote>
     * "Each transactional event listener is wrapped into an aspect that marks
     * that log entry as completed if the execution of the listener succeeds.
     * In case the listener fails, the log entry stays untouched so that
     * retry mechanisms can be deployed."
     * </blockquote>
     *
     * <p>Reference:
     * <a href="https://docs.spring.io/spring-modulith/reference/events.html">Spring Modulith Reference -- Event Publication Registry</a>
     * <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">Spring Framework Reference -- Declarative Transaction Management (REQUIRES_NEW)</a>
     */
    private void generateSlotsForDate(LocalDate date) {
        List<ProviderAvailabilityRule> rules = ruleRepository.findByDayOfWeek(date.getDayOfWeek());
        if (rules.isEmpty()) {
            log.info("No availability rules configured for {}", date.getDayOfWeek());
            return;
        }
        for (ProviderAvailabilityRule rule : rules) {
            try {
                // Through the bean, never self-invoked: the REQUIRES_NEW
                // propagation only applies behind the proxy.
                slotGenerator.generateFrom(rule, date);
            } catch (DataAccessException e) {
                // Best-effort per rule: the rule's own transaction already
                // rolled back cleanly; log and continue the batch.
                // Programming errors (NPE, etc.) MUST propagate to the event
                // publication log so Spring Modulith can retry them.
                log.error("Failed to generate slot from rule {} (data error)", rule.getId(), e);
            }
        }
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Observed(name = "availability.timeoff.create")
    public ProviderTimeOffResponse createTimeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        ProviderTimeOff saved = timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
        return ProviderTimeOffResponse.from(saved);
    }

    @Override
    public void bookSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId) {
        AvailabilitySlot slot = repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .orElseThrow(() -> new ConflictException("No available slot for provider " + providerId));
        // R2: the claim is one atomic entity mutation — booked flag and owner
        // set together (AvailabilitySlot.markBooked). Concurrent claims on the
        // same row are settled by the entity's @Version optimistic lock
        // (BaseEntity — the losing flush fails its transaction and rolls the
        // confirm back), with V73's one-live-row-per-window index underneath.
        slot.markBooked(bookingId);
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
    }

    @Override
    public void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId) {
        // R2: only the hold THIS booking placed is released — the ownership
        // filter makes a non-owner cancel (the PENDING sibling of the holder)
        // a no-op instead of freeing a CONFIRMED booking's window.
        repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt)
                .filter(slot -> bookingId.equals(slot.getHeldByBookingId()))
                .ifPresent(AvailabilitySlot::markAvailable);
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
    }
}
