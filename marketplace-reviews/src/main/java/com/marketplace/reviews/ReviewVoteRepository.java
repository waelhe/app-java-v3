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

    /**
     * W4 (yelp-level plan §5 — G29, "أصوات مفيد تراكمية"): the reviewer's
     * cumulative helpful-vote total — every live vote (an unvoted vote is
     * a soft-deleted row, gone from the count) received on any of his live
     * reviews, whatever their moderation state: the badge measures the
     * community's endorsement the reviewer accumulated, not a row's
     * current visibility (a review hidden after its votes keeps the votes
     * it genuinely received). The {@code @SoftDelete} filter applies to
     * both the root and the subquery — dead votes and dead reviews never
     * count.
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(v) from ReviewVote v
            where v.reviewId in (select r.id from Review r where r.reviewerId = :reviewerId)
            """)
    long countByReviewerId(UUID reviewerId);
}
