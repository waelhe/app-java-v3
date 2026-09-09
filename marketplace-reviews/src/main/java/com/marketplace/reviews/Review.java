package com.marketplace.reviews;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * I8 note (internal free plan §6): the provider_id column means "the
 * provider profile this review belongs to" — for
 * {@code CONSUMER_TO_PROVIDER} reviews that is the REVIEWED provider (the
 * historical semantics, unchanged); for {@code PROVIDER_TO_CONSUMER}
 * reviews it is the AUTHORING provider (the L21 stats resolution resolves
 * the review to its provider either way). The reviewed CONSUMER of a
 * reverse review lives in {@code reviewee_id} (users.id space).
 */
@Entity
@Table(name = "reviews")
@Audited
public class Review extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Min(1)
    @Max(5)
    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** L21: the provider's reply — one per review (null until the first). */
    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    @Column(name = "replied_at")
    private Instant repliedAt;

    /**
     * I8: the review direction — {@link ReviewDirection#CONSUMER_TO_PROVIDER}
     * is the historical default (every pre-I8 row carries it via V45's
     * DEFAULT); {@link ReviewDirection#PROVIDER_TO_CONSUMER} is the reverse
     * (the provider rates the booking's consumer).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private ReviewDirection direction = ReviewDirection.CONSUMER_TO_PROVIDER;

    /**
     * I8: the reviewed party of a REVERSE review — the consumer's user id
     * (users.id space). {@code null} on forward reviews, whose reviewed
     * provider already lives in {@code providerId} (profiles.id space — no
     * mixed id spaces, no denormalization).
     */
    @Column(name = "reviewee_id")
    private UUID revieweeId;

    protected Review() {
    }

    public Review(UUID id, UUID bookingId, UUID reviewerId,
                  UUID providerId, Integer rating, String comment) {
        this.id = id;
        this.bookingId = bookingId;
        this.reviewerId = reviewerId;
        this.providerId = providerId;
        this.rating = rating;
        this.comment = comment;
        this.direction = ReviewDirection.CONSUMER_TO_PROVIDER;
        this.revieweeId = null;
    }

    public static Review create(UUID bookingId, UUID reviewerId,
                                 UUID providerId, Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        return new Review(UUID.randomUUID(), bookingId, reviewerId, providerId, rating, comment);
    }

    /**
     * I8: the reverse review — the provider (reviewerId, users.id space)
     * rates the booking's consumer (revieweeId, users.id space);
     * providerId is the AUTHORING provider's profile id (the L21 stats
     * resolution key). The same rating floor as the forward direction.
     */
    public static Review createReverse(UUID bookingId, UUID reviewerId,
                                        UUID providerId, UUID revieweeId,
                                        Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        Review review = new Review(UUID.randomUUID(), bookingId, reviewerId, providerId,
                rating, comment);
        review.direction = ReviewDirection.PROVIDER_TO_CONSUMER;
        review.revieweeId = revieweeId;
        return review;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getReviewerId() { return reviewerId; }
    public UUID getProviderId() { return providerId; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }
    public String getReply() { return reply; }
    public Instant getRepliedAt() { return repliedAt; }
    public ReviewDirection getDirection() { return direction; }
    public UUID getRevieweeId() { return revieweeId; }

    public void update(Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        this.rating = rating;
        this.comment = comment;
    }

    /**
     * L21 (roadmap §5): the provider reply — unique by construction: the
     * column pair is null until the first reply and a second attempt is a
     * conflict, never an overwrite (acceptance 1: «ردّ واحد لكل تقييم (فريد)»).
     */
    public void reply(String reply) {
        if (this.reply != null) {
            throw new ConflictException("Review already has a provider reply");
        }
        this.reply = reply;
        this.repliedAt = Instant.now();
    }
}