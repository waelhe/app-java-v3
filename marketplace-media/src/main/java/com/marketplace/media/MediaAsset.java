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
 * A media asset (photo) attached to a provider listing.
 *
 * <p>Lifecycle: {@link MediaAssetStatus#PENDING_UPLOAD PENDING_UPLOAD} (a presigned
 * PUT URL was issued) then {@link MediaAssetStatus#UPLOADED UPLOADED} (the object
 * was verified present in the storage bucket via HeadObject). The object key is
 * server-generated ({@code listings/{listingId}/{uuid}.{ext}}) — no client input
 * ever reaches the key, so path traversal into other prefixes is impossible by
 * construction.
 *
 * <p>Cross-module references follow the house convention: {@code listingId} and
 * {@code providerId} are plain UUID columns with no JPA relation across module
 * boundaries (same as {@code Review.bookingId}); the catalog module resolves
 * listings through the {@code ListingPriceProvider} port in shared.
 */
@Entity
@Table(name = "media_assets")
@Audited
public class MediaAsset extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaAssetStatus status = MediaAssetStatus.PENDING_UPLOAD;

    /**
     * Display order within the listing (1-based, insertion order). Not unique
     * across soft-deleted rows — ordering only, never identity.
     */
    @Column(name = "position", nullable = false)
    private Integer position;

    protected MediaAsset() {
    }

    public MediaAsset(UUID id, UUID listingId, UUID providerId, String objectKey,
                      String contentType, Long sizeBytes, Integer position) {
        this.id = id;
        this.listingId = listingId;
        this.providerId = providerId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.position = position;
        this.status = MediaAssetStatus.PENDING_UPLOAD;
    }

    /**
     * Creates a new asset awaiting a client upload. The object key is supplied by
     * the service layer (server-generated).
     */
    public static MediaAsset create(UUID listingId, UUID providerId, String objectKey,
                                    String contentType, Long sizeBytes, Integer position) {
        return new MediaAsset(UUID.randomUUID(), listingId, providerId, objectKey,
                contentType, sizeBytes, position);
    }

    /**
     * Marks the asset as uploaded — only legal from PENDING_UPLOAD after the
     * storage layer verified the object exists with the declared size and type.
     */
    public void markUploaded() {
        this.status.validateTransitionTo(MediaAssetStatus.UPLOADED);
        this.status = MediaAssetStatus.UPLOADED;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getProviderId() { return providerId; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public MediaAssetStatus getStatus() { return status; }
    public Integer getPosition() { return position; }
}
