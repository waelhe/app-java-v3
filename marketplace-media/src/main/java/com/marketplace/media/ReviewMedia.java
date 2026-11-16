package com.marketplace.media;

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
 * W1 (yelp-level plan §4.4/G5 — ارتباط وسائط المراجعة): a photo attached
 * to a REVIEW. The listing-media twin minus the listing:
 * {@code MediaAsset.listing_id} is NOT NULL by design (V32 — cross-module
 * ownership is resolved through ports, not nullable FKs) and cannot carry
 * a review's media, so the review gets its own table, its own audit
 * mirror ({@code review_media_aud}, V74) and its own upload/read path —
 * the same two-phase presign/confirm flow, the same server-generated
 * object key ({@code review-media/{reviewId}/{uuid}.{ext}}), the same
 * PENDING_UPLOAD→UPLOADED lifecycle ({@code MediaAssetStatus} reused
 * verbatim), the same per-owner position serialization.
 *
 * <p>Ownership: {@code uploader_id} is the review author's user id (the
 * A1 space) — the reviews module's {@code ReviewLookupPort} is the gate
 * (the author uploads/manages; the public read follows the review's own
 * visibility).
 */
@Entity
@Table(name = "review_media")
@Audited
public class ReviewMedia extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaAssetStatus status = MediaAssetStatus.PENDING_UPLOAD;

    /** Display order within the review (1-based, insertion order) — ordering only, never identity. */
    @Column(name = "position", nullable = false)
    private Integer position;

    protected ReviewMedia() {
    }

    private ReviewMedia(UUID id, UUID reviewId, UUID uploaderId, String objectKey,
                        String contentType, Long sizeBytes, Integer position) {
        this.id = id;
        this.reviewId = reviewId;
        this.uploaderId = uploaderId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.position = position;
        this.status = MediaAssetStatus.PENDING_UPLOAD;
    }

    public static ReviewMedia create(UUID reviewId, UUID uploaderId, String objectKey,
                                     String contentType, Long sizeBytes, Integer position) {
        return new ReviewMedia(UUID.randomUUID(), reviewId, uploaderId, objectKey,
                contentType, sizeBytes, position);
    }

    /** Marks the asset uploaded — only legal from PENDING_UPLOAD after the storage layer verified the object. */
    public void markUploaded() {
        this.status.validateTransitionTo(MediaAssetStatus.UPLOADED);
        this.status = MediaAssetStatus.UPLOADED;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public UUID getUploaderId() { return uploaderId; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public MediaAssetStatus getStatus() { return status; }
    public Integer getPosition() { return position; }
}
