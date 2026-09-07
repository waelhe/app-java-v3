package com.marketplace.provider;

import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * L21 (feature-expansion roadmap §5) — the stored rating average on the
 * provider profile, driven by the EXISTING review events. Both events carry
 * the review id only, so the provider side resolves the reviewed provider and
 * the recomputed aggregate through {@link ReviewStatsPort} — the same
 * event-id + lookup-port shape {@code LedgerPaymentEventListener} applies
 * with {@code PaymentIntentLookupPort}.
 *
 * <p>{@code @ApplicationModuleListener} dispatch is AFTER_COMMIT + async in a
 * {@code REQUIRES_NEW} transaction: the reviews row the stats are recomputed
 * from is already committed when this runs.
 */
@Component
public class ProviderReviewStatsListener {

    private static final Logger log = LoggerFactory.getLogger(ProviderReviewStatsListener.class);

    private final ReviewStatsPort reviewStatsPort;
    private final ProviderService providerService;

    public ProviderReviewStatsListener(ReviewStatsPort reviewStatsPort, ProviderService providerService) {
        this.reviewStatsPort = reviewStatsPort;
        this.providerService = providerService;
    }

    @ApplicationModuleListener
    public void onReviewCreated(ReviewCreatedEvent event) {
        refresh(event.reviewId());
    }

    @ApplicationModuleListener
    public void onReviewUpdated(ReviewUpdatedEvent event) {
        refresh(event.reviewId());
    }

    private void refresh(UUID reviewId) {
        reviewStatsPort.findStatsByReviewId(reviewId).ifPresent(stats -> {
            providerService.applyRatingAverage(stats.providerId(), stats.averageRating());
            log.info("Provider rating average refreshed: providerId={}, average={}, count={}",
                    stats.providerId(), stats.averageRating(), stats.reviewCount());
        });
    }
}
