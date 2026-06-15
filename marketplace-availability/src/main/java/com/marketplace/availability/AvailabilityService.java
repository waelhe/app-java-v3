package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final AvailabilitySlotRepository repository;
    private final ProviderAvailabilityRuleRepository ruleRepository;
    private final ProviderTimeOffRepository timeOffRepository;

    public AvailabilityService(AvailabilitySlotRepository repository,
                               ProviderAvailabilityRuleRepository ruleRepository,
                               ProviderTimeOffRepository timeOffRepository) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
    }

    @CacheEvict(cacheNames = "availability", allEntries = true)
    public AvailabilitySlot createSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        return repository.save(AvailabilitySlot.open(providerId, startsAt, endsAt));
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

    public ProviderAvailabilityRule createRule(UUID providerId, java.time.DayOfWeek dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime) {
        return ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime));
    }

    private static final int SLOT_GENERATION_DAYS_AHEAD = 7;

    @ApplicationModuleListener
    public void onDayHasPassed(DayHasPassed event) {
        LocalDate date = event.getDate();
        for (int i = 0; i < SLOT_GENERATION_DAYS_AHEAD; i++) {
            generateSlotsForDate(date.plusDays(i));
        }
    }

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
            } catch (Exception e) {
                log.error("Failed to generate slot from rule {}", rule.getId(), e);
            }
        }
    }

    @Observed(name = "availability.timeoff.create")
    @CacheEvict(cacheNames = "availability", allEntries = true)
    public ProviderTimeOff createTimeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        return timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt));
    }

    @Override
    @CacheEvict(cacheNames = "availability", allEntries = true)
    public void bookSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        AvailabilitySlot slot = repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .orElseThrow(() -> new ConflictException("No available slot for provider " + providerId));
        slot.markBooked();
    }

    @Override
    @CacheEvict(cacheNames = "availability", allEntries = true)
    public void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt)
                .ifPresent(AvailabilitySlot::markAvailable);
    }
}
