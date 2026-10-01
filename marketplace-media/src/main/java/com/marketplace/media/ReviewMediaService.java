package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * W1 (yelp-level plan §4.4 — ارتباط وسائط المراجعة): review-photo business
 * logic — the {@code MediaService} contract scoped to a review instead of
 * a listing: presigned upload bound to a server-generated key, HeadObject
 * verification at confirm, presigned reads, soft delete with the
 * after-commit storage cleanup. {@code MediaAsset.listing_id} is NOT NULL
 * by design and the organic review has no listing at all (G5), so the
 * review carries its own table and path.
 *
 * <p><b>Ownership through the reviews port (the module law):</b> the
 * review's author ({@code ReviewLookupPort.findAuthorId} — the A1
 * users.id space) owns its media; admin passes. The PUBLIC read follows
 * the review's own visibility contract: a PUBLISHED review's photos are
 * public, any other moderation state is the author's (or an admin's)
 * view only — everyone else answers the honest 404.
 */
@Service
@Transactional
public class ReviewMediaService {

    private static final Logger log = LoggerFactory.getLogger(ReviewMediaService.class);

    /** The object-key prefix of this surface — its own namespace by construction. */
    private static final String OBJECT_KEY_PREFIX = "review-media";

    private final ReviewMediaRepository reviewMediaRepository;
    private final ObjectProvider<S3MediaStorage> storage;
    private final MediaProperties properties;
    private final ReviewLookupPort reviewLookupPort;
    private final CurrentUserProvider currentUserProvider;

    public ReviewMediaService(ReviewMediaRepository reviewMediaRepository,
                              ObjectProvider<S3MediaStorage> storage,
                              MediaProperties properties,
                              ReviewLookupPort reviewLookupPort,
                              CurrentUserProvider currentUserProvider) {
        this.reviewMediaRepository = reviewMediaRepository;
        this.storage = storage;
        this.properties = properties;
        this.reviewLookupPort = reviewLookupPort;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Issues a presigned PUT URL for a new asset of the given review.
     * Validates type and size BEFORE anything is signed, resolves the
     * review's author through the reviews port (the honest 404 for an
     * unknown review) and verifies the caller owns it.
     */
    @Observed(name = "media.review.upload.request")
    @PreAuthorize("isAuthenticated()")
    public ReviewMediaUploadView requestUpload(UUID reviewId, String contentType,
                                               long sizeBytes, Authentication authentication) {
        S3MediaStorage s3 = MediaUploadRules.requireStorage(storage);
        String normalizedType = MediaUploadRules.normalizeContentType(contentType);
        MediaUploadRules.validateContentType(properties, normalizedType);
        MediaUploadRules.validateSize(properties, sizeBytes);

        UUID authorId = reviewLookupPort.findAuthorId(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));
        verifyReviewOwnership(authorId, authentication);

        // Position allocation must serialize per review (the MediaAsset
        // #241 shape — the same advisory transaction lock).
        reviewMediaRepository.lockPositionAllocation(reviewId.toString());

        // W1 §4.5 posture (CodeRabbit W1 r4, adopted from the root): the
        // anti-abuse gates do not stop at creation — the upload channel is
        // part of the same surface. The count check runs while the advisory
        // lock is held, so the limit holds under concurrent requests too.
        // PENDING uploads count (they occupy positions until cleaned), so an
        // abandoned upload still consumes one of the review's slots.
        long attached = reviewMediaRepository.countByReviewId(reviewId);
        if (attached >= properties.limits().maxAssetsPerReview()) {
            throw new BadRequestException(
                    "A review carries at most " + properties.limits().maxAssetsPerReview()
                            + " photos (including pending uploads)");
        }

        String objectKey = MediaUploadRules.buildObjectKey(OBJECT_KEY_PREFIX, reviewId, normalizedType);
        ReviewMedia asset = reviewMediaRepository.save(ReviewMedia.create(
                reviewId, authorId, objectKey, normalizedType,
                // The highest allocated position — deleted rows included — plus
                // one: never re-issue a position a remaining row already holds
                // (greptile W1 r10).
                sizeBytes, reviewMediaRepository.findMaxPositionByReviewId(reviewId) + 1));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new ReviewMediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * The review's author must be the caller (admin passes) — the
     * {@code MediaService.verifyOwnership} shape, with the review's author
     * as the ownership fact (there is no provider profile between the
     * caller and the asset here).
     */
    private void verifyReviewOwnership(UUID authorId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        if (!authorId.equals(currentUserId)) {
            throw new AccessDeniedException("You do not own this review's media");
        }
    }

    private void verifyAssetOwnership(ReviewMedia asset, Authentication authentication) {
        verifyReviewOwnership(asset.getUploaderId(), authentication);
    }

    @Transactional(readOnly = true)
    public ReviewMedia getById(UUID id) {
        return reviewMediaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review media not found: " + id));
    }

    /**
     * Confirms an upload: verifies via HeadObject that the object exists
     * with exactly the declared type and size, then moves the asset to
     * UPLOADED. No thumbnail pipeline here (that pipeline is
     * MediaAsset-scoped by contract) — the original object is what the
     * read path serves.
     */
    @Observed(name = "media.review.upload.confirm")
    @PreAuthorize("isAuthenticated()")
    public ReviewMediaView confirmUpload(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = MediaUploadRules.requireStorage(storage);
        ReviewMedia asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        boolean verified = s3.verifyUploaded(asset.getObjectKey(), asset.getContentType(), asset.getSizeBytes());
        if (!verified) {
            throw new BadRequestException(
                    "Object not found in storage (or type/size mismatch) for media: " + mediaId);
        }
        asset.markUploaded();
        return toView(asset, s3.presignDownload(asset.getObjectKey()));
    }

    /**
     * The read path: presigned GET URLs for every UPLOADED asset of the
     * review, in display order. Visibility follows the review's own
     * contract — PUBLISHED is public; any other moderation state is the
     * author's (or an admin's) view, everyone else answers the honest 404
     * (a non-published review never leaks its existence through its media
     * either).
     */
    @Transactional(readOnly = true)
    public List<ReviewMediaView> listByReview(UUID reviewId, Authentication authentication) {
        S3MediaStorage s3 = MediaUploadRules.requireStorage(storage);
        if (reviewLookupPort.findVisibleAuthorId(reviewId).isEmpty()) {
            UUID authorId = reviewLookupPort.findAuthorId(reviewId)
                    .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));
            boolean admin = authentication != null && currentUserProvider.isAdmin(authentication);
            UUID caller = authentication == null
                    ? null
                    : currentUserProvider.tryGetCurrentUserId(authentication).orElse(null);
            if (!admin && !authorId.equals(caller)) {
                throw new ResourceNotFoundException("Review not found: " + reviewId);
            }
        }
        return reviewMediaRepository
                .findByReviewIdAndStatusOrderByPositionAsc(reviewId, MediaAssetStatus.UPLOADED)
                .stream()
                .map(asset -> toView(asset, s3.presignDownload(asset.getObjectKey())))
                .toList();
    }

    /**
     * Soft-deletes the asset and removes the storage object best-effort
     * AFTER the commit (the MediaService delete contract verbatim).
     */
    @Observed(name = "media.review.asset.delete")
    @PreAuthorize("isAuthenticated()")
    public void delete(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = MediaUploadRules.requireStorage(storage);
        ReviewMedia asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        reviewMediaRepository.delete(asset);
        String objectKey = asset.getObjectKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    removeStorageObject(s3, objectKey);
                }
            });
        } else {
            // No active transaction (unit tests) — remove immediately.
            removeStorageObject(s3, objectKey);
        }
    }

    private ReviewMediaView toView(ReviewMedia asset, String downloadUrl) {
        return new ReviewMediaView(
                asset.getId(),
                asset.getReviewId(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getStatus().name(),
                asset.getPosition(),
                downloadUrl,
                asset.getCreatedAt());
    }

    /**
     * Storage-side removal failure is logged, never fatal — bucket
     * lifecycle rules own orphans (the MediaService D3 contract).
     */
    private void removeStorageObject(S3MediaStorage s3, String objectKey) {
        try {
            s3.deleteObject(objectKey);
        } catch (RuntimeException ex) {
            log.warn("Storage object removal failed for key {} (bucket lifecycle will own it): {}",
                    objectKey, ex.getMessage());
        }
    }

    /** Upload request carrier — the client PUTs its bytes to {@code uploadUrl}, then calls confirm. */
    @Schema(description = "Presigned review-media upload response")
    public record ReviewMediaUploadView(
            @Schema(description = "The persisted review-media asset id",
                    example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            UUID mediaId,
            @Schema(description = "Server-generated object key",
                    example = "review-media/7c9e6679-7425-40de-944b-e07fc1f90ae7/1b2c3d4e-....jpg")
            String objectKey,
            @Schema(description = "Presigned PUT URL (the TTL is urlLifetime)")
            String uploadUrl,
            @Schema(description = "How long the presigned URL stays valid", example = "PT15M")
            Duration urlLifetime) {
    }

    /** Read/confirm response — the presigned GET URL is freshly signed per call. */
    @Schema(description = "Review media asset with a presigned read link")
    public record ReviewMediaView(
            @Schema(description = "The review-media asset id",
                    example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            UUID id,
            @Schema(description = "The review this asset belongs to")
            UUID reviewId,
            @Schema(description = "Image content type", example = "image/jpeg")
            String contentType,
            @Schema(description = "Object size in bytes", example = "418381")
            long sizeBytes,
            @Schema(description = "Asset lifecycle status", example = "UPLOADED")
            String status,
            @Schema(description = "Display order within the review (1-based)", example = "1")
            int position,
            @Schema(description = "Presigned GET URL of the original object")
            String downloadUrl,
            @Schema(description = "Creation timestamp", example = "2026-10-01T12:00:00Z")
            java.time.Instant createdAt) {
    }
}
