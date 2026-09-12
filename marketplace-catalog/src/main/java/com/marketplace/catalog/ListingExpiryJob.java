package com.marketplace.catalog;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * L33 (realestate systems plan §5): the listing expiry job — the
 * {@code EventPublicationCleanup} pattern (a framework-scheduled component,
 * cron + UTC zone). Every ACTIVE listing whose {@code expires_at} has
 * passed moves to PAUSED with the DURABLE {@code EXPIRED} marker (the
 * same state machine transition as the provider's pause — D-R7: zero
 * machine changes), in pages, idempotently (the status itself is the
 * guard: a re-run finds nothing new).
 *
 * <p>Cache freshness rides the existing AFTER_COMMIT relay: ONE
 * {@link CacheInvalidationRequested} after the batch (the relay evicts
 * cache NAMES, so one event covers every row changed in the transaction).
 *
 * <p>The boundary case "expires_at exactly now" stays ACTIVE (the predicate
 * is strictly {@code before now} — a listing expiring this instant still
 * serves its last request; the next tick catches it).
 */
@Component
public class ListingExpiryJob {

    /** Page size — bounded work per transaction, the house batch discipline. */
    static final int BATCH_SIZE = 500;

    private final ProviderListingRepository listingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ListingExpiryJob(ProviderListingRepository listingRepository,
                            ApplicationEventPublisher eventPublisher,
                            Clock clock) {
        this.listingRepository = listingRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Runs every 30 minutes (the plan's cadence — the classifieds' expiry
     * precision is half an hour, not a second).
     */
    @Scheduled(cron = "0 */30 * * * ?", zone = "UTC")
    @Transactional
    public void pauseExpiredListings() {
        Instant now = clock.instant();
        int paused = 0;
        int page = 0;
        List<ProviderListing> expired;
        do {
            expired = listingRepository.findByStatusAndExpiresAtBefore(
                    ListingStatus.ACTIVE, now, org.springframework.data.domain.PageRequest.of(page, BATCH_SIZE))
                    .getContent();
            if (!expired.isEmpty()) {
                for (ProviderListing listing : expired) {
                    listing.pauseForExpiry();
                    paused++;
                }
                listingRepository.saveAll(expired);
            }
            if (expired.size() == BATCH_SIZE) {
                page++;
            }
        } while (expired.size() == BATCH_SIZE);
        if (paused > 0) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(
                    CatalogService.CATALOG_CACHE_NAMES));
        }
    }
}
