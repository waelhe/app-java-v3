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
 * I8 note (internal free plan §6): the provider_id column carries the
 * provider's USER id — the A1 convention, the physically measured fact
 * (V6: {@code provider_id uuid not null references users(id)}; documented
 * in {@code AuthHelper.ownsProvider} and the Phase 2/3 export/purge
 * contracts). For {@code CONSUMER_TO_PROVIDER} reviews it is the REVIEWED
 * provider's user id (the historical semantics, unchanged); for
 * {@code PROVIDER_TO_CONSUMER} reviews it is the AUTHORING provider's
 * user id. The reviewed CONSUMER of a reverse review lives in
 * {@code reviewee_id} (users.id space).
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
     * provider's user id already lives in {@code provider_id} (users.id
     * space — the V6 FK; one space across both columns, no denormalization).
     * The old "profiles.id space" claim was the documented false
     * assumption the §9 surgical gate fix corrected.
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
     * providerId stores the AUTHORING provider's USER id (A1 — V6's FK:
     * {@code references users(id)}; the write path stores the booking's
     * {@code providerInfo.providerId()}, itself a users.id). The same
     * rating floor as the forward direction.
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