package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.SlotWindowStats;
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

    private static final String PROVIDER_STATUS_VERIFIED = "VERIFIED";

    private final AvailabilitySlotRepository slotRepository;
    private final com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    public AvailabilityLookupAdapter(AvailabilitySlotRepository slotRepository,
                                     com.marketplace.shared.api.ProviderLookupPort providerLookupPort) {
        this.slotRepository = slotRepository;
        this.providerLookupPort = providerLookupPort;
    }

    /**
     * Row 11 (agent findings 2026-10-26 14:33 export): the SEARCH-window
     * consumption gate — the windowed search's "available provider ids"
     * carry only ELIGIBLE providers. The slot query answers the pure slot
     * fact (an open window exists); the eligibility fact belongs to the
     * provider module and is resolved in ONE batch through the
     * {@code findAllByUserIds} seam (the users.id space the providerId
     * universe carries — the AuthHelper discipline). A SUSPENDED (or
     * unverified) profile therefore neither publishes nor generates
     * bookable slots nor surfaces as an available window: the bookable
     * surface is coherent end to end, and reinstatement (the machine's own
     * SUSPENDED -> VERIFIED edge) reopens it with no cleanup owed.
     */
    @Override
    public Set<UUID> findAvailableProviderIds(Instant startsAt, Instant endsAt) {
        Set<UUID> withOpenSlots = slotRepository.findAvailableProviderIds(startsAt, endsAt);
        if (withOpenSlots.isEmpty()) {
            return withOpenSlots;
        }
        // ONE batch resolution for the whole window's provider set — the
        // findAllByUserIds seam (never a per-provider loop).
        java.util.Map<UUID, com.marketplace.shared.api.ProviderSummary> summaries =
                providerLookupPort.findAllByUserIds(withOpenSlots);
        return withOpenSlots.stream()
                .filter(providerId -> summaries.containsKey(providerId)
                        && PROVIDER_STATUS_VERIFIED.equals(summaries.get(providerId).status()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * L25 (feature-expansion roadmap §5): the provider-stats slot aggregates
     * — a read-only delegation to the constructor-projection query, exposed
     * through the same shared-api port the search module already consumes
     * (the port's charter is cross-module availability lookups; the
     * extension is documented in the provider-stats PR).
     */
    @Override
    public SlotWindowStats findProviderSlotStats(UUID providerId, Instant from, Instant to) {
        return slotRepository.findProviderSlotStats(providerId, from, to);
    }
}
