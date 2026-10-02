package com.marketplace.reviews.spi;

import com.marketplace.reviews.ReviewsViewService;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PublishedReviewView;
import com.marketplace.shared.api.PublishedReviewsPort;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * W1 (yelp-level plan §4.4/§4.5): the reviews module's implementation of
 * the {@link PublishedReviewsPort} cross-module contract (the
 * {@code ReviewStatsAdapter} pattern verbatim). The composed page — the
 * visibility gate, the three batch-resolved identity blocks — is owned by
 * {@link ReviewsViewService}, the read-side assembly; this side only
 * translates the neutral page request through {@link SpringPagination}
 * (the documented interop corner) and maps the module's own response
 * record onto the shared projection the provider public page renders.
 */
@Component
@Transactional(readOnly = true)
public class ReviewsPublishedPageAdapter implements PublishedReviewsPort {

    private final ReviewsViewService reviewsViewService;

    public ReviewsPublishedPageAdapter(ReviewsViewService reviewsViewService) {
        this.reviewsViewService = reviewsViewService;
    }

    @Override
    public PagedResponse<PublishedReviewView> findPublishedByProviderUserId(UUID providerUserId, PagedRequest request) {
        Pageable pageable = SpringPagination.toPageable(request);
        return PagedResponse.of(reviewsViewService.listByProvider(providerUserId, pageable)
                .map(review -> new PublishedReviewView(
                        review.id(),
                        review.rating(),
                        review.comment(),
                        review.reply(),
                        review.repliedAt(),
                        review.createdAt(),
                        review.origin(),
                        review.reviewerId(),
                        review.reviewerName(),
                        review.reviewerReviewCount(),
                        review.helpfulCount())));
    }
}
