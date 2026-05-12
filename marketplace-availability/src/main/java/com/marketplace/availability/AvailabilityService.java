package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {
    private final AvailabilityRuleRepository ruleRepository;
    private final ProviderTimeOffRepository timeOffRepository;

    public AvailabilityService(AvailabilityRuleRepository ruleRepository, ProviderTimeOffRepository timeOffRepository) {
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
    }

    public ProviderAvailabilityRule addRule(UUID providerId, Integer dayOfWeek, java.time.LocalTime startsAt, java.time.LocalTime endsAt, Integer slotMinutes) {
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Availability rule start must be before end");
        }
        return ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startsAt, endsAt, slotMinutes));
    }

    public ProviderTimeOff addTimeOff(UUID providerId, Instant startsAt, Instant endsAt, String reason) {
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Time off start must be before end");
        }
        return timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt, reason));
    }

    @Transactional(readOnly = true)
    public List<ProviderAvailabilityRule> listRules(UUID providerId) { return ruleRepository.findByProviderId(providerId); }

    @Transactional(readOnly = true)
    public List<ProviderTimeOff> listTimeOff(UUID providerId) { return timeOffRepository.findByProviderId(providerId); }

    @Transactional(readOnly = true)
    public List<AvailabilitySlot> slots(UUID providerId, UUID listingId, LocalDate date) {
        int day = date.getDayOfWeek().getValue();
        return ruleRepository.findByProviderId(providerId).stream()
                .filter(rule -> rule.getDayOfWeek() == day)
                .map(rule -> new AvailabilitySlot(providerId, listingId,
                        date.atTime(rule.getStartsAt()).toInstant(ZoneOffset.UTC),
                        date.atTime(rule.getEndsAt()).toInstant(ZoneOffset.UTC), true))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAvailable(UUID providerId, UUID listingId, Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null) {
            return;
        }
        if (!timeOffRepository.findOverlapping(providerId, startsAt, endsAt).isEmpty()) {
            throw new IllegalStateException("Provider is not available for the requested time");
        }
    }
}
