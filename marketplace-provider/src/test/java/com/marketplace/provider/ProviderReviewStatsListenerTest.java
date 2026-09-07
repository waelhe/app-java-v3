package com.marketplace.provider;

import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProviderReviewStatsListenerTest {

    private final ProviderService providerService = mock(ProviderService.class);
    private final ProviderReviewStatsListener listener =
            new ProviderReviewStatsListener(providerService);

    @Test
    void reviewCreatedEvent_forwardsToTheService() {
        UUID reviewId = UUID.randomUUID();

        listener.onReviewCreated(new ReviewCreatedEvent(reviewId));

        verify(providerService).refreshRatingAverage(reviewId);
    }

    @Test
    void reviewUpdatedEvent_forwardsToTheService() {
        UUID reviewId = UUID.randomUUID();

        listener.onReviewUpdated(new ReviewUpdatedEvent(reviewId));

        verify(providerService).refreshRatingAverage(reviewId);
    }
}
