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
 * A media asset (photo) attached to a domain target — since L48 either a
 * provider listing or a neighborhood post (the D-C3 generalization: one
 * media pipeline, an explicit target per row).
 *
 * <p>Lifecycle: {@link MediaAssetStatus#PENDING_UPLOAD PENDING_UPLOAD} (a presigned
 * PUT URL was issued) then {@link MediaAssetStatus#UPLOADED UPLOADED} (the object
 * was verified present in the storage bucket via HeadObject). The object key is
 * server-generated ({@code listings/{listingId}/{uuid}.{ext}} for the listing
 * target, {@code posts/{postId}/{uuid}.{ext}} for the post target) — no client
 * input ever reaches the key, so path traversal into other prefixes is impossible
 * by construction.
 *
 * <p>Cross-module references follow the house convention: {@code listingId},
 * {@code postId} and {@code providerId} are plain UUID columns with no JPA
 * relation across module boundaries (same as {@code Review.bookingId}); the
 * catalog module resolves listings through the {@code ListingPriceProvider}
 * port in shared, the community module resolves posts through
 * {@code PostLookupPort} — and {@code providerId} stays the uploading USER's
 * id for both targets (the measured A1 fact): the listing flow resolves it
 * through the provider profile, the post flow carries the author's user id
 * directly (a member author need not hold a provider profile).
 */
@Entity
@Table(name = "media_assets")
@Audited
public class MediaAsset extends BaseEntity {

    @Id
    private UUID id;

    /** L48: the row's explicit target discriminator (never a nullable guess). */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_kind", nullable = false, length = 20)
    private MediaOwnerKind ownerKind = MediaOwnerKind.LISTING;

    @Column(name = "listing_id")
    private UUID listingId;

    /** L48: the post target — NULL for every listing row (V76 CHECK). */
    @Column(name = "post_id")
    private UUID postId;

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

    /**
     * L28 (feature-expansion roadmap §5): the deterministic thumbnail key
     * {@code {objectKey}/thumb} once background processing has run. NULL =
     * processing pending (or failed and awaiting the documented resubmission
     * retry); non-null and equal to {@code objectKey} = non-processable, an
     * already-small original, or a source over the raster budget (header-
     * declared pixels above the configured limit — never decoded) — the
     * thumbnail IS the original by design.
     */
    @Column(name = "thumb_object_key", length = 500)
    private String thumbObjectKey;

    protected MediaAsset() {
    }

    private MediaAsset(UUID id, MediaOwnerKind ownerKind, UUID listingId, UUID postId,
                       UUID providerId, String objectKey, String contentType, Long sizeBytes,
                       Integer position) {
        this.id = id;
        this.ownerKind = ownerKind;
        this.listingId = listingId;
        this.postId = postId;
        this.providerId = providerId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.position = position;
        this.status = MediaAssetStatus.PENDING_UPLOAD;
    }

    /**
     * Creates a new LISTING-targeted asset awaiting a client upload. The
     * object key is supplied by the service layer (server-generated).
     */
    public static MediaAsset create(UUID listingId, UUID providerId, String objectKey,
                                    String contentType, Long sizeBytes, Integer position) {
        return new MediaAsset(UUID.randomUUID(), MediaOwnerKind.LISTING, listingId, null,
                providerId, objectKey, contentType, sizeBytes, position);
    }

    /**
     * L48: creates a new POST-targeted asset awaiting a client upload — the
     * author's user id rides {@code providerId} (the A1 ownership basis,
     * which for posts is the author directly: a member need not hold a
     * provider profile). The object key is supplied by the service layer
     * (server-generated, under the {@code posts/} prefix).
     */
    public static MediaAsset createForPost(UUID postId, UUID authorId, String objectKey,
                                           String contentType, Long sizeBytes, Integer position) {
        return new MediaAsset(UUID.randomUUID(), MediaOwnerKind.POST, null, postId,
                authorId, objectKey, contentType, sizeBytes, position);
    }

    /**
     * Marks the asset as uploaded — only legal from PENDING_UPLOAD after the
     * storage layer verified the object exists with the declared size and type.
     */
    public void markUploaded() {
        this.status.validateTransitionTo(MediaAssetStatus.UPLOADED);
        this.status = MediaAssetStatus.UPLOADED;
    }

    /**
     * L28: pins the thumbnail key after processing — write-once: an existing
     * value makes the whole processing idempotent (the listener re-running
     * after a resubmission must not re-store or flip the pointer).
     */
    public void recordThumbKey(String thumbKey) {
        this.thumbObjectKey = thumbKey;
    }

    @Override
    public UUID getId() { return id; }
    public MediaOwnerKind getOwnerKind() { return ownerKind; }
    public UUID getListingId() { return listingId; }
    public UUID getPostId() { return postId; }
    public UUID getProviderId() { return providerId; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public MediaAssetStatus getStatus() { return status; }
    public Integer getPosition() { return position; }
    public String getThumbObjectKey() { return thumbObjectKey; }
}
