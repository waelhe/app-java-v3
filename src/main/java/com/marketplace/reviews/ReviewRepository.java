package com.marketplace.reviews;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByProviderId(UUID providerId, Pageable pageable);

    Page<Review> findByReviewerId(UUID reviewerId, Pageable pageable);

    boolean existsByBookingId(UUID bookingId);
}