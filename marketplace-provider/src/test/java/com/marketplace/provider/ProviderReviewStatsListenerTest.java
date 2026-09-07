package com.marketplace.provider;

import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderReviewStatsListenerTest {

    private final ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
    private final ProviderService providerService = mock(ProviderService.class);
    private final ProviderReviewStatsListener listener =
            new ProviderReviewStatsListener(reviewStatsPort, providerService);

    @Test
    void reviewCreatedEvent_storesRecomputedAverage() {
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(reviewStatsPort.findStatsByReviewId(reviewId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.25, 4)));

        listener.onReviewCreated(new ReviewCreatedEvent(reviewId));

        verify(providerService).applyRatingAverage(eq(providerId), eq(4.25));
    }

    @Test
    void reviewUpdatedEvent_storesRecomputedAverage() {
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(reviewStatsPort.findStatsByReviewId(reviewId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 3.0, 2)));

        listener.onReviewUpdated(new ReviewUpdatedEvent(reviewId));

        verify(providerService).applyRatingAverage(eq(providerId), eq(3.0));
    }

    @Test
    void unknownReview_isSkippedSilently() {
        UUID reviewId = UUID.randomUUID();
        when(reviewStatsPort.findStatsByReviewId(reviewId)).thenReturn(Optional.empty());

        listener.onReviewCreated(new ReviewCreatedEvent(reviewId));

        verify(providerService, never()).applyRatingAverage(
                org.mockito.ArgumentMatchers.any(UUID.class), anyDouble());
    }
}
