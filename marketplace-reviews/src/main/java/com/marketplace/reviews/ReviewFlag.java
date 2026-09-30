package com.marketplace.reviews;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W1 (yelp-level plan §4.5 — إشارات الاحتيال التجميعية): an INTERNAL fraud
 * signal recorded on a review for the moderation queue. First release
 * contract, verbatim from the plan: the signals are recorded, never
 * auto-blocked ("لا حجب تلقائيًا في الإصدار الأول — شفافية على التقنيين").
 *
 * <p>The three signal types the plan names:
 * <ul>
 *   <li>{@link FlagType#BURST_ON_PROVIDER} — a batch of near-simultaneous
 *       organic reviews on one provider;</li>
 *   <li>{@link FlagType#NEW_ACCOUNT_ACTIVITY} — registration and review
 *       close together in time (the account is young but past the hard
 *       seven-day floor);</li>
 *   <li>{@link FlagType#TEXT_SIMILARITY} — the same reviewer's comment
 *       text repeats on another review (normalized comparison).</li>
 * </ul>
 * The DB CHECK (V74) pins the vocabulary (D-N7 — every enumerated column
 * carries its DB-level membership guard).
 */
@Entity
@Table(name = "review_flags")
@Audited
public class ReviewFlag extends BaseEntity {

    public enum FlagType {
        BURST_ON_PROVIDER,
        NEW_ACCOUNT_ACTIVITY,
        TEXT_SIMILARITY
    }

    @Id
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag_type", nullable = false, length = 40)
    private FlagType flagType;

    @Column(name = "details", length = 500)
    private String details;

    protected ReviewFlag() {
    }

    private ReviewFlag(UUID id, UUID reviewId, FlagType flagType, String details) {
        this.id = id;
        this.reviewId = reviewId;
        this.flagType = flagType;
        this.details = details;
    }

    public static ReviewFlag create(UUID reviewId, FlagType flagType, String details) {
        return new ReviewFlag(UUID.randomUUID(), reviewId, flagType, details);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public FlagType getFlagType() { return flagType; }
    public String getDetails() { return details; }
}
