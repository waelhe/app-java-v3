package com.marketplace.reviews.spi;

import com.marketplace.reviews.ReviewResponse;
import com.marketplace.reviews.ReviewsViewService;
import com.marketplace.shared.api.PublishedReviewView;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 (§4.4/§4.5): the provider public page's reviews block — the adapter
 * delegates to the read-side assembly with the provider USER id and the
 * asked page, and maps the module's composed response onto the shared
 * projection field for field (the ReviewStatsAdapter test shape).
 */
class ReviewsPublishedPageAdapterTest {

    private final ReviewsViewService reviewsViewService = mock(ReviewsViewService.class);
    private final ReviewsPublishedPageAdapter adapter = new ReviewsPublishedPageAdapter(reviewsViewService);

    @Test
    void delegatesWithTheUserIdAndThePage_andMapsFieldForField() {
        UUID providerUserId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        Instant createdAt = Instant.parse("2026-09-20T00:00:00Z");
        Instant repliedAt = Instant.parse("2026-09-21T00:00:00Z");
        ReviewResponse composed = new ReviewResponse(reviewId, null, 5, "Sourdough sells out by noon",
                "Thank you", "CONSUMER_TO_PROVIDER", repliedAt, createdAt, createdAt, "ORGANIC",
                "PUBLISHED", null, "Nour", 7L, 3L);
        when(reviewsViewService.listByProvider(providerUserId, pageable))
                .thenReturn(new PageImpl<>(List.of(composed), pageable, 1));

        var page = adapter.findPublishedByProviderUserId(providerUserId, pageable);

        verify(reviewsViewService).listByProvider(providerUserId, pageable);
        assertThat(page.getTotalElements()).isEqualTo(1);
        PublishedReviewView row = page.getContent().getFirst();
        assertThat(row.id()).isEqualTo(reviewId);
        assertThat(row.rating()).isEqualTo(5);
        assertThat(row.comment()).isEqualTo("Sourdough sells out by noon");
        assertThat(row.reply()).isEqualTo("Thank you");
        assertThat(row.repliedAt()).isEqualTo(repliedAt);
        assertThat(row.createdAt()).isEqualTo(createdAt);
        assertThat(row.origin()).isEqualTo("ORGANIC");
        assertThat(row.reviewerName()).isEqualTo("Nour");
        assertThat(row.reviewerReviewCount()).isEqualTo(7L);
        assertThat(row.helpfulCount()).isEqualTo(3L);
    }

    @Test
    void emptyPage_passesThroughUntouched() {
        UUID providerUserId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByProvider(eq(providerUserId), eq(pageable)))
                .thenReturn(new PageImpl<>(List.<ReviewResponse>of(), pageable, 0));

        var page = adapter.findPublishedByProviderUserId(providerUserId, pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
