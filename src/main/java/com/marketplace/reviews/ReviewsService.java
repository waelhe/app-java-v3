package com.marketplace.reviews;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ReviewsService {

    private final ReviewRepository reviewRepository;
    private final SecurityUtils securityUtils;

    public ReviewsService(ReviewRepository reviewRepository, SecurityUtils securityUtils) {
        this.reviewRepository = reviewRepository;
        this.securityUtils = securityUtils;
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
    public Review create(UUID bookingId, UUID reviewerId, UUID providerId,
                         Integer rating, String comment) {
        if (reviewRepository.existsByBookingId(bookingId)) {
            throw new IllegalStateException("Review already exists for booking: " + bookingId);
        }
        return reviewRepository.save(Review.create(bookingId, reviewerId, providerId, rating, comment));
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public Review update(UUID id, Integer rating, String comment, Authentication authentication) {
        Review review = getById(id);
        verifyOwnership(review, authentication);
        review.update(rating, comment);
        return review;
    }

    private void verifyOwnership(Review review, Authentication authentication) {
        UUID currentUserId = securityUtils.getCurrentUserId(authentication);
        if (!review.getReviewerId().equals(currentUserId) && !securityUtils.isAdmin(authentication)) {
            throw new IllegalArgumentException("You did not write this review");
        }
    }
}