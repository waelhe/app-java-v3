package com.marketplace.reviews;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W1 (yelp-level plan §4.5 — أصوات «مفيد»): one user's "helpful" vote on
 * one review — the quality signal the reviewer-trust score will later feed
 * on (G6→G26).
 *
 * <p>Uniqueness is (review × voter), enforced twice the house way: the
 * service's explicit 409 first, the V74 partial unique index
 * ({@code uq_review_votes_review_voter}, live rows only) as the
 * concurrent-insert backstop. The soft delete makes an unvote a state
 * flip, so a re-vote after unvoting is legal by construction (the partial
 * index excludes the dead row).
 */
@Entity
@Table(name = "review_votes")
@Audited
public class ReviewVote extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    /** The voter — a {@code users.id} (the A1 convention; no cross-module JPA relation). */
    @Column(name = "voter_id", nullable = false)
    private UUID voterId;

    protected ReviewVote() {
    }

    private ReviewVote(UUID id, UUID reviewId, UUID voterId) {
        this.id = id;
        this.reviewId = reviewId;
        this.voterId = voterId;
    }

    public static ReviewVote create(UUID reviewId, UUID voterId) {
        return new ReviewVote(UUID.randomUUID(), reviewId, voterId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public UUID getVoterId() { return voterId; }
}
