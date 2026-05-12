package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {

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

    public AvailabilitySlot createSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        return repository.save(AvailabilitySlot.open(providerId, startsAt, endsAt));
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlot> getSlots(UUID providerId, Instant from, Instant to) {
        return repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt) {
        boolean slotAvailable = repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        boolean hasTimeOffConflict = timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        return slotAvailable && !hasTimeOffConflict;
    }

    public ProviderAvailabilityRule createRule(UUID providerId, java.time.DayOfWeek dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime) {
        return ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime));
    }

    public ProviderTimeOff createTimeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        return timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt));
    }
}
