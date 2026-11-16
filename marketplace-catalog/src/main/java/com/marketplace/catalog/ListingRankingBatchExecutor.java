package com.marketplace.catalog;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * W3 (yelp-level plan §5 — G18): the daily ranking job's ONE-BATCH
 * transaction boundary — the {@code ListingExpiryBatchExecutor} pattern
 * applied to a scan whose match set does NOT shrink (ranking a listing
 * leaves it ACTIVE), so the pagination is a KEYSET over the id order (the
 * saved-search scan's own adopted fix: an offset would shift under
 * concurrent inserts and skip rows; {@code id > :afterId} cannot).
 *
 * <p>Each call is {@code REQUIRES_NEW} — one bounded transaction per
 * batch, its own persistence context — and composes the batch's provider
 * stats in ONE grouped query (the toSummaryPage batch discipline, never
 * per-row). The completeness reads stay per-listing: the L38 score's own
 * ports (media count + property details) are leaf lookups with no batch
 * form; at the ranking scale (the clean-ACTIVE roster) that is two
 * bounded queries per listing, the job's own measured cost point.
 *
 * <p>The cache eviction rides INSIDE the transaction (the AFTER_COMMIT
 * relay's own law): every batch's commit evicts the catalog/search cache
 * names — a re-ranked page must not serve yesterday's order for up to the
 * 1h TTL when one commit can make it fresh.
 */
@Component
public class ListingRankingBatchExecutor {

    private final ProviderListingRepository listingRepository;
    private final ReviewStatsPort reviewStatsPort;
    private final MediaLookupPort mediaLookupPort;
    private final PropertyDetailsPort propertyDetailsPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ListingRankingBatchExecutor(ProviderListingRepository listingRepository,
                                       ReviewStatsPort reviewStatsPort,
                                       MediaLookupPort mediaLookupPort,
                                       PropertyDetailsPort propertyDetailsPort,
                                       ApplicationEventPublisher eventPublisher,
                                       Clock clock) {
        this.listingRepository = listingRepository;
        this.reviewStatsPort = reviewStatsPort;
        this.mediaLookupPort = mediaLookupPort;
        this.propertyDetailsPort = propertyDetailsPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Ranks ONE keyset batch of clean-ACTIVE listings in its own
     * transaction.
     *
     * @param afterId the keyset cursor (the previous batch's last id;
     *                {@code null} starts the run)
     * @param batchSize the bounded work unit (the orchestrator's constant)
     * @return the batch's LAST id (the next cursor) — {@code null} when the
     *         batch came back empty (the run is drained)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID rankOneBatch(UUID afterId, int batchSize) {
        Instant now = clock.instant();
        List<ProviderListing> batch = listingRepository.findRankableAfter(
                afterId, now, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return null;
        }
        // ONE grouped query for the whole batch's distinct providers.
        Set<UUID> providerIds = batch.stream()
                .map(ProviderListing::getProviderId)
                .collect(Collectors.toSet());
        Map<UUID, ReviewStats> ratings = reviewStatsPort.findStatsByProviderUserIds(providerIds);

        boolean anyChange = false;
        for (ProviderListing listing : batch) {
            // The L38 completeness composition — the controller's own read
            // shape verbatim (the pure function over the two leaf ports).
            PropertyDetailsPort.PropertyView property =
                    propertyDetailsPort.findByListingId(listing.getId()).orElse(null);
            long uploadedPhotos = mediaLookupPort.countUploadedByListing(listing.getId());
            int completeness = ListingCompletenessResponse.of(listing, uploadedPhotos, property).percent();
            double score = ListingRankingFormula.score(
                    ratings.get(listing.getProviderId()), completeness, listing.getCreatedAt(), now);
            // Write only what moved: a stable score (no drift within the
            // double's precision) produces no UPDATE and no audit revision —
            // the recency decay makes fresh listings move daily by design.
            if (listing.getRankingScore() == null || Math.abs(listing.getRankingScore() - score) > 1e-9) {
                listing.applyRankingScore(score);
                anyChange = true;
            }
        }
        if (anyChange) {
            listingRepository.saveAll(batch);
            // AFTER_COMMIT relay: published inside THIS batch's transaction —
            // every re-ranked batch's commit carries its own eviction.
            eventPublisher.publishEvent(new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        }
        return batch.get(batch.size() - 1).getId();
    }
}
