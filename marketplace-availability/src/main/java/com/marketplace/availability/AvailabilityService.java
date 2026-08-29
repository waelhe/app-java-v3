package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private static final int SLOT_GENERATION_DAYS_AHEAD = 7;
    private static final Set<String> AVAILABILITY_CACHE_NAMES = Set.of("availability");

    public AvailabilityService(AvailabilitySlotRepository repository,
                               ProviderAvailabilityRuleRepository ruleRepository,
                               ProviderTimeOffRepository timeOffRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
        this.eventPublisher = eventPublisher;
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public AvailabilitySlot createSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        AvailabilitySlot saved = repository.save(AvailabilitySlot.open(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_CACHE_NAMES));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlot> getSlots(UUID providerId, Instant from, Instant to) {
        return repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("availability")
    public boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt) {
        boolean slotAvailable = repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        boolean hasTimeOffConflict = timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        return slotAvailable && !hasTimeOffConflict;
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public ProviderAvailabilityRule createRule(UUID providerId, java.time.DayOfWeek dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime) {
        return ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime));
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
     * <p><b>Exception handling policy (Spring Modulith event publication log)</b>:
     * The prior implementation used {@code catch (Exception e)} which swallowed
     * <em>all</em> exceptions -- including programming errors (NPE, ClassCastException)
     * and data-access failures. Per Spring Modulith Reference ("The Event Publication
     * Registry"):
     * <blockquote>
     * "Each transactional event listener is wrapped into an aspect that marks that log
     * entry as completed if the execution of the listener succeeds. In case the listener
     * fails, the log entry stays untouched so that retry mechanisms can be deployed."
     * </blockquote>
     * A {@code catch (Exception)} that returns normally makes the aspect see "success"
     * and marks the log entry COMPLETED -- silently losing the event and defeating
     * the retry mechanism.
     *
     * <p><b>Fix</b>: narrow the catch to {@link org.springframework.dao.DataAccessException}
     * only. This preserves the "best-effort per rule" behavior (a single bad rule does
     * not abort the whole batch) while letting programming errors propagate to the
     * event publication log for retry. DataAccessException is the Spring Data root
     * exception for all data-access failures (constraint violations, deadlocks, etc.).
     *
     * <p>Reference:
     * <a href="https://docs.spring.io/spring-modulith/reference/events.html">Spring Modulith Reference -- Event Publication Registry</a>
     * <a href="https://docs.spring.io/spring-framework/reference/data-access/dao.html">Spring Framework Reference -- Data Access</a>
     */
    private void generateSlotsForDate(LocalDate date) {
        List<ProviderAvailabilityRule> rules = ruleRepository.findByDayOfWeek(date.getDayOfWeek());
        if (rules.isEmpty()) {
            log.info("No availability rules configured for {}", date.getDayOfWeek());
            return;
        }
        for (ProviderAvailabilityRule rule : rules) {
            try {
                Instant startsAt = date.atTime(rule.getStartTime()).toInstant(ZoneOffset.UTC);
                Instant endsAt = date.atTime(rule.getEndTime()).toInstant(ZoneOffset.UTC);
                if (repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(
                        rule.getProviderId(), startsAt, endsAt).isEmpty()) {
                    createSlot(rule.getProviderId(), startsAt, endsAt);
                    log.info("Generated slot for provider {}: {} - {}", rule.getProviderId(), startsAt, endsAt);
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // Best-effort per rule: log data-access failures but continue the batch.
                // Programming errors (NPE, etc.) MUST propagate to the event publication
                // log so Spring Modulith can retry them.
                log.error("Failed to generate slot from rule {} (data error)", rule.getId(), e);
            }
        }
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Observed(name = "availability.timeoff.create")
    public ProviderTimeOff createTimeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        ProviderTimeOff saved = timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_CACHE_NAMES));
        return saved;
    }

    @Override
    public void bookSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        AvailabilitySlot slot = repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .orElseThrow(() -> new ConflictException("No available slot for provider " + providerId));
        slot.markBooked();
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_CACHE_NAMES));
    }

    @Override
    public void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt)
                .ifPresent(AvailabilitySlot::markAvailable);
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_CACHE_NAMES));
    }
}
