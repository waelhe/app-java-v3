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

    public AvailabilityService(AvailabilitySlotRepository repository) {
        this.repository = repository;
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
        return repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
    }
}
