package com.marketplace.reviews;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewVoteRepository extends JpaRepository<ReviewVote, UUID> {

    boolean existsByReviewIdAndVoterId(UUID reviewId, UUID voterId);

    Optional<ReviewVote> findByReviewIdAndVoterId(UUID reviewId, UUID voterId);

    List<ReviewVote> findByReviewIdIn(Collection<UUID> reviewIds);

    /**
     * W1 (§4.5): the helpful-vote counts for one page of reviews in a
     * single grouped query (the batch-resolution rule — no per-row
     * count queries).
     */
    @org.springframework.data.jpa.repository.Query("""
            select new com.marketplace.reviews.ReviewVoteCount(v.reviewId, count(v))
            from ReviewVote v
            where v.reviewId in :reviewIds
            group by v.reviewId
            """)
    List<ReviewVoteCount> countByReviewIds(Collection<UUID> reviewIds);
}
