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

    @Column(name = "booking_id")
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

    /** The two stored origin values — held by the V72 DB check, not a Java enum (the plan's §4.2 explicit decision). */
    public static final String ORIGIN_BOOKING = "BOOKING";
    public static final String ORIGIN_ORGANIC = "ORGANIC";

    /**
     * W1 (yelp-level plan §4.2 — G2): the review's origin. 'BOOKING' for
     * every pre-W1 row (V72's DEFAULT backfills losslessly) and for every
     * verified review; 'ORGANIC' for the no-booking general review. Plain
     * String by the plan's own design decision: the two values live in the
     * database CHECK (chk_reviews_origin_kind), not in a Java enum
     * reference — a future third value widens by migration, not by
     * rebuilding this type.
     */
    @Column(name = "origin", nullable = false, length = 20)
    private String origin = ORIGIN_BOOKING;

    /** §4.2: the organic review's optional listing target (V72's FK; null on every verified review). */
    @Column(name = "listing_id")
    private UUID listingId;

    /**
     * W1 (§4.5): the moderation status — the soft state column. PUBLISHED
     * is the public surface (every pre-W1 row's V72 DEFAULT — zero visible
     * change); PENDING_REVIEW is the first-reviews queue; HIDDEN_BY_MODERATOR
     * is the moderated-away terminal state.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 20)
    private ReviewModerationStatus moderationStatus = ReviewModerationStatus.PUBLISHED;

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
     * W1 (yelp-level plan §4.1/§4.2 — the organic path): a general review
     * with NO booking — {@code origin = 'ORGANIC'}, {@code booking_id} null
     * (the V72 cross-column check pins the pairing), an optional listing
     * target. The reviewer identity is the caller; the provider id is the
     * reviewed provider's USER id (A1 — the same space every verified row
     * carries). The caller (ReviewsService.createOrganic) has already
     * applied the §4.5 anti-abuse gates; this factory owns only the shape
     * and the rating floor.
     */
    public static Review createOrganic(UUID reviewerId, UUID providerId, UUID listingId,
                                       Integer rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        Review review = new Review(UUID.randomUUID(), null, reviewerId, providerId, rating, comment);
        review.origin = ORIGIN_ORGANIC;
        review.listingId = listingId;
        return review;
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
    public String getOrigin() { return origin; }
    public UUID getListingId() { return listingId; }
    public ReviewModerationStatus getModerationStatus() { return moderationStatus; }

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

    /**
     * W1 (§4.5): the creation-time queueing of an organic review — the
     * account's first reviews await an explicit moderation approval. Only
     * legal while the row is still PUBLISHED (the newborn default): a
     * stored-state re-entry is a caller bug, never a silent flip.
     */
    public void queueForReview() {
        if (this.moderationStatus != ReviewModerationStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "A review can only be queued for review at creation — current status: " + moderationStatus);
        }
        this.moderationStatus = ReviewModerationStatus.PENDING_REVIEW;
    }

    /**
     * W1 (§4.5): the moderation queue's approve — PENDING_REVIEW →
     * PUBLISHED, nothing else is legal (a second approval or an approval
     * of a hidden/published row answers the honest 409).
     */
    public void approveByModerator() {
        if (this.moderationStatus != ReviewModerationStatus.PENDING_REVIEW) {
            throw new ConflictException(
                    "Only a PENDING_REVIEW review can be approved — current status: " + moderationStatus);
        }
        this.moderationStatus = ReviewModerationStatus.PUBLISHED;
    }

    /**
     * W1 (§4.5): the moderation hide — the report-resolve outcome and the
     * queue's reject both land here. Terminal by construction (every read
     * gate is PUBLISHED-only); returns whether a real flip happened (the
     * documented skip for an already-hidden row: callers gate first, this
     * answer keeps them honest).
     */
    public boolean hideByModerator() {
        if (this.moderationStatus == ReviewModerationStatus.HIDDEN_BY_MODERATOR) {
            return false;
        }
        this.moderationStatus = ReviewModerationStatus.HIDDEN_BY_MODERATOR;
        return true;
    }

    /** The public-surface predicate every read gate composes. */
    public boolean isPublished() {
        return this.moderationStatus == ReviewModerationStatus.PUBLISHED;
    }
}