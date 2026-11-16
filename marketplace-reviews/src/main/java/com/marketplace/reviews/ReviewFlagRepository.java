package com.marketplace.reviews;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReviewFlagRepository extends JpaRepository<ReviewFlag, UUID> {

    List<ReviewFlag> findByReviewIdIn(Collection<UUID> reviewIds);
}
