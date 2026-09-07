package com.marketplace.provider;

import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * L21 (feature-expansion roadmap §5) — the stored rating average on the
 * provider profile, driven by the EXISTING review events. The events carry
 * the review id only; {@code ProviderService#refreshRatingAverage} resolves
 * the reviewed provider, locks the profile row and recomputes the aggregate
 * inside its own transaction (see the service javadoc for the concurrency
 * contract).
 *
 * <p>{@code @ApplicationModuleListener} dispatch is AFTER_COMMIT + async in a
 * {@code REQUIRES_NEW} transaction: the reviews row the stats are recomputed
 * from is already committed when this runs.
 */
@Component
public class ProviderReviewStatsListener {

    private final ProviderService providerService;

    public ProviderReviewStatsListener(ProviderService providerService) {
        this.providerService = providerService;
    }

    @ApplicationModuleListener
    public void onReviewCreated(ReviewCreatedEvent event) {
        providerService.refreshRatingAverage(event.reviewId());
    }

    @ApplicationModuleListener
    public void onReviewUpdated(ReviewUpdatedEvent event) {
        providerService.refreshRatingAverage(event.reviewId());
    }
}
