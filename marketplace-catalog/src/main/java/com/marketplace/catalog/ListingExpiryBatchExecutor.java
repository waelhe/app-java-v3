package com.marketplace.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * L33 + CodeRabbit PR #299 round 1: the expiry job's ONE-BATCH transaction
 * boundary. The original design looped every page inside one
 * {@code @Transactional} — a large backlog kept the whole scan in one
 * persistence context and one transaction (timeout and memory pressure),
 * and the offset advanced while processed rows LEFT the matching set
 * (status flips to PAUSED), skipping every BATCH_SIZE-th remainder.
 *
 * <p>This executor owns the root fix for both: each call is
 * {@code REQUIRES_NEW} — one bounded transaction per batch, its own
 * persistence context — and every call re-queries <b>page zero</b>: the
 * rows it just paused no longer match {@code status = ACTIVE}, so the
 * "next page" of remaining expired rows IS page zero again. The batch
 * count returned to the orchestrator decides whether another pass runs
 * (a short page = the backlog is drained).
 *
 * <p>The cache eviction event is published INSIDE this transaction: the
 * relay is an {@code @TransactionalEventListener(AFTER_COMMIT)} — a
 * non-transactional publish would never reach it. Each batch's commit
 * therefore carries its own eviction (idempotent — the relay clears
 * cache NAMES; a few redundant evictions per run are the freshness
 * price of bounded transactions, measured in cache misses not
 * correctness).
 */
@Component
public class ListingExpiryBatchExecutor {

    private final ProviderListingRepository listingRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ListingExpiryBatchExecutor(ProviderListingRepository listingRepository,
                                      org.springframework.context.ApplicationEventPublisher eventPublisher,
                                      Clock clock) {
        this.listingRepository = listingRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Pauses ONE page of expired ACTIVE listings in its own transaction.
     *
     * @return the number of listings paused in this batch (a short page —
     *         less than {@link ListingExpiryJob#BATCH_SIZE} — means the
     *         backlog is drained for this run)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int pauseOneBatch() {
        Instant now = clock.instant();
        List<ProviderListing> expired = listingRepository.findByStatusAndExpiresAtBefore(
                        ListingStatus.ACTIVE, now,
                        org.springframework.data.domain.PageRequest.of(0, ListingExpiryJob.BATCH_SIZE))
                .getContent();
        for (ProviderListing listing : expired) {
            listing.pauseForExpiry();
        }
        if (!expired.isEmpty()) {
            listingRepository.saveAll(expired);
            // AFTER_COMMIT relay: published inside THIS batch's transaction,
            // evicted at its commit — the freshness point of the boundary
            eventPublisher.publishEvent(new com.marketplace.shared.api.CacheInvalidationRequested(
                    CatalogService.CATALOG_CACHE_NAMES));
        }
        return expired.size();
    }
}
