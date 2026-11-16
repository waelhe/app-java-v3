package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.UUID;

public interface SeasonalRateRepository
        extends JpaRepository<SeasonalRate, UUID>, RevisionRepository<SeasonalRate, UUID, Integer> {

    /**
     * L26: the listing's seasonal ranges ordered by start date — the shape
     * {@code PricingService.effectiveTotalCents} scans per day and the
     * overlap validation walks.
     */
    List<SeasonalRate> findByListingIdOrderByFromDateAsc(UUID listingId);
}
