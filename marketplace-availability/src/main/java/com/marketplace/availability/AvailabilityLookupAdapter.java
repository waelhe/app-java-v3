package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * L27 (feature-expansion roadmap §5): the availability module's
 * implementation of the {@link AvailabilityLookupPort} cross-module contract
 * (the {@code ReviewStatsAdapter} / {@code ProviderLookupPort} house
 * pattern). A read-only delegation to the bulk predicate in
 * {@link AvailabilitySlotRepository#findAvailableProviderIds} — the same
 * strict-overlap semantics as {@code AvailabilityService.isAvailable}, one
 * query for all providers at once so the search module never loops over
 * candidates.
 */
@Component
@Transactional(readOnly = true)
public class AvailabilityLookupAdapter implements AvailabilityLookupPort {

    private final AvailabilitySlotRepository slotRepository;

    public AvailabilityLookupAdapter(AvailabilitySlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Override
    public Set<UUID> findAvailableProviderIds(Instant startsAt, Instant endsAt) {
        return slotRepository.findAvailableProviderIds(startsAt, endsAt);
    }
}
