package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ReviewsService {

    private final ReviewRepository reviewRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingParticipantProvider bookingParticipantProvider;

    public ReviewsService(ReviewRepository reviewRepository,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher,
                          BookingParticipantProvider bookingParticipantProvider) {
        this.reviewRepository = reviewRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.bookingParticipantProvider = bookingParticipantProvider;
    }

    @Transactional(readOnly = true)
    public Review getById(UUID id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Review> listByProvider(UUID providerId, Pageable pageable) {
        return reviewRepository.findByProviderId(providerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Review> listByReviewer(UUID reviewerId, Pageable pageable) {
        return reviewRepository.findByReviewerId(reviewerId, pageable);
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public Review create(UUID bookingId, UUID reviewerId,
                         Integer rating, String comment) {
        if (reviewRepository.existsByBookingId(bookingId)) {
            throw new IllegalStateException("Review already exists for booking: " + bookingId);
        }

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);

        if (!bookingInfo.consumerId().equals(reviewerId)) {
            throw new AccessDeniedException("Only the booking consumer can submit a review");
        }

        if (!"COMPLETED".equals(bookingInfo.status())) {
            throw new IllegalStateException("Cannot review a booking that is not COMPLETED");
        }

        Review saved = reviewRepository.save(
                Review.create(bookingId, reviewerId, bookingInfo.providerId(), rating, comment));
        eventPublisher.publishEvent(new ReviewCreatedEvent(saved.getId()));
        return saved;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public Review update(UUID id, Integer rating, String comment, Authentication authentication) {
        Review review = getById(id);
        verifyOwnership(review, authentication);
        review.update(rating, comment);
        return review;
    }

    private void verifyOwnership(Review review, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!review.getReviewerId().equals(currentUserId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("You did not write this review");
        }
    }
}
