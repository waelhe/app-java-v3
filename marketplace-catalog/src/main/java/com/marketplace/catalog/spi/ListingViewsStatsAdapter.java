package com.marketplace.catalog.spi;

import com.marketplace.catalog.ListingViewsDailyRepository;
import com.marketplace.shared.api.ListingViewStats;
import com.marketplace.shared.api.ListingViewsStatsPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider-module
 * facing read — the {@code LedgerStatsAdapter} shape (L25): the catalog
 * module owns the daily buckets, so the windowed per-listing sum lives
 * here, behind the shared port. The repository query carries the whole
 * contract (window semantics, honest-empty, deterministic order); this
 * adapter is the module-boundary translation only.
 */
@Component
public class ListingViewsStatsAdapter implements ListingViewsStatsPort {

    private final ListingViewsDailyRepository repository;

    public ListingViewsStatsAdapter(ListingViewsDailyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ListingViewStats> findViewTotalsForProviderSince(UUID providerUserId, LocalDate sinceInclusive) {
        return repository.sumViewTotalsForProviderSince(providerUserId, sinceInclusive);
    }
}
