package com.marketplace.reviews;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:06:20+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class ReviewMapperImpl implements ReviewMapper {

    @Override
    public ReviewResponse toResponse(Review review) {
        if ( review == null ) {
            return null;
        }

        UUID id = null;
        UUID bookingId = null;
        Integer rating = null;
        String comment = null;
        String reply = null;
        String direction = null;
        Instant repliedAt = null;
        Instant createdAt = null;
        Instant updatedAt = null;
        String origin = null;
        String moderationStatus = null;
        UUID listingId = null;
        UUID reviewerId = null;

        id = review.getId();
        bookingId = review.getBookingId();
        rating = review.getRating();
        comment = review.getComment();
        reply = review.getReply();
        if ( review.getDirection() != null ) {
            direction = review.getDirection().name();
        }
        repliedAt = review.getRepliedAt();
        createdAt = review.getCreatedAt();
        updatedAt = review.getUpdatedAt();
        origin = review.getOrigin();
        if ( review.getModerationStatus() != null ) {
            moderationStatus = review.getModerationStatus().name();
        }
        listingId = review.getListingId();
        reviewerId = review.getReviewerId();

        String reviewerName = null;
        long reviewerReviewCount = 0L;
        long helpfulCount = 0L;

        ReviewResponse reviewResponse = new ReviewResponse( id, bookingId, rating, comment, reply, direction, repliedAt, createdAt, updatedAt, origin, moderationStatus, listingId, reviewerId, reviewerName, reviewerReviewCount, helpfulCount );

        return reviewResponse;
    }
}
