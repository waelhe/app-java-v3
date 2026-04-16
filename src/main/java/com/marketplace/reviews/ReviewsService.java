package com.marketplace.reviews;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ReviewsService {

    private final ReviewRepository reviewRepository;

    public ReviewsService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public Review getById(UUID id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + id));
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
    public Review update(UUID id, Integer rating, String comment) {
        Review review = getById(id);
        review.update(rating, comment);
        return review;
    }
}