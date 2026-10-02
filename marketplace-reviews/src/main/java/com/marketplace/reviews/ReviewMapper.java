package com.marketplace.reviews;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    /**
     * The entity-fields mapping. The three batch-resolved blocks
     * (reviewerName / reviewerReviewCount / helpfulCount) are ignored here
     * and composed by the overload below — {@code ReviewsViewService} is
     * their only caller-side owner.
     */
    @Mapping(target = "reviewerName", ignore = true)
    @Mapping(target = "reviewerReviewCount", ignore = true)
    @Mapping(target = "helpfulCount", ignore = true)
    ReviewResponse toResponse(Review review);

    /**
     * W1 (§4.4/§4.5) — the fully composed response: the entity fields plus
     * the three batch-resolved blocks (the reviewer's public display name,
     * his published-review count, the review's helpful-vote count). The
     * default method keeps the base mapping visible and the composition
     * explicit (the documented MapStruct default-method behavior).
     */
    default ReviewResponse toResponse(Review review, String reviewerName,
                                      long reviewerReviewCount, long helpfulCount) {
        ReviewResponse base = toResponse(review);
        return new ReviewResponse(
                base.id(), base.bookingId(), base.rating(), base.comment(), base.reply(),
                base.direction(), base.repliedAt(), base.createdAt(), base.updatedAt(),
                base.origin(), base.moderationStatus(), base.listingId(),
                reviewerName, reviewerReviewCount, helpfulCount);
    }
}

