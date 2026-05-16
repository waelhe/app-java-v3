package com.marketplace.reviews;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

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

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

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
    }

    public static Review create(UUID bookingId, UUID reviewerId,
                                 UUID providerId, Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        return new Review(UUID.randomUUID(), bookingId, reviewerId, providerId, rating, comment);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getReviewerId() { return reviewerId; }
    public UUID getProviderId() { return providerId; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }

    public void update(Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        this.rating = rating;
        this.comment = comment;
    }
}