package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.MediaUploadedEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Listing media business logic — the provider-owned half of roadmap item B1
 * (G-PROD-1): issue a presigned upload URL bound to a server-generated object
 * key, verify the object after upload, expose presigned read URLs.
 *
 * <p>Ownership follows the exact house pattern of {@code CatalogService.verifyOwnership}:
 * the listing is resolved through the existing {@link ListingPriceProvider} port
 * (implemented by catalog), the provider record through {@link ProviderLookupPort},
 * and the current user through {@link CurrentUserProvider}. The only shared
 * addition is {@link ServiceUnavailableException} — the house pattern for
 * problem-detail exceptions (ResourceNotFound/BadRequest/Conflict).
 *
 * <p>Observation policy (layer 6): commands only — {@code media.upload.request},
 * {@code media.upload.confirm}, {@code media.asset.delete}. Reads are measured by
 * the framework's own {@code http.server.requests}.
 */
@Service
@Transactional
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** Server-controlled extension mapping — the client never touches the key. */
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final MediaAssetRepository mediaAssetRepository;
    private final ObjectProvider<S3MediaStorage> storage;
    private final MediaProperties properties;
    private final ListingPriceProvider listingPriceProvider;
    private final ProviderLookupPort providerLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public MediaService(MediaAssetRepository mediaAssetRepository,
                        ObjectProvider<S3MediaStorage> storage,
                        MediaProperties properties,
                        ListingPriceProvider listingPriceProvider,
                        ProviderLookupPort providerLookupPort,
                        CurrentUserProvider currentUserProvider,
                        ApplicationEventPublisher eventPublisher) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storage = storage;
        this.properties = properties;
        this.listingPriceProvider = listingPriceProvider;
        this.providerLookupPort = providerLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Issues a presigned PUT URL for a new asset of the given listing. Validates
     * the type allowlist and size cap BEFORE anything is signed, resolves the
     * listing, and verifies the caller owns it.
     */
    @Observed(name = "media.upload.request")
    @PreAuthorize("hasRole('PROVIDER')")
    public MediaUploadView requestUpload(UUID listingId, String contentType,
                                         long sizeBytes, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        String normalizedType = normalizeContentType(contentType);
        validateContentType(normalizedType);
        validateSize(sizeBytes);

        ListingPriceProvider.ListingInfo listing = listingPriceProvider.getListingInfo(listingId);
        verifyListingOwnership(listing.providerId(), authentication);

        // Display-position allocation must be atomic per listing (CodeRabbit
        // #241): countByListingId()+1 inside a transaction does NOT serialize
        // concurrent transactions — two uploads can read the same count and
        // persist the same position. The advisory transaction lock below is
        // held until the surrounding transaction commits, so per-listing
        // allocations are serialized in the database (hashtextextended maps
        // the listing UUID text to a single bigint lock key, PG13+).
        mediaAssetRepository.lockListingPositionAllocation(listingId.toString());

        String objectKey = buildObjectKey(listingId, normalizedType);
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.create(
                listingId, listing.providerId(), objectKey, normalizedType,
                sizeBytes, (int) (mediaAssetRepository.countByListingId(listingId) + 1)));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new MediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * Confirms an upload: verifies via HeadObject that the object exists with
     * exactly the declared type and size, then moves the asset to UPLOADED
     * and publishes {@link MediaUploadedEvent} (L28) — the thumbnail pipeline
     * listens AFTER_COMMIT, so the event only exists once this state is
     * durable. A failed verification leaves the asset PENDING — confirmable
     * again.
     */
    @Observed(name = "media.upload.confirm")
    @PreAuthorize("hasRole('PROVIDER')")
    public MediaAssetView confirmUpload(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        boolean verified = s3.verifyUploaded(asset.getObjectKey(), asset.getContentType(), asset.getSizeBytes());
        if (!verified) {
            throw new BadRequestException(
                    "Object not found in storage (or type/size mismatch) for media: " + mediaId);
        }
        asset.markUploaded();
        eventPublisher.publishEvent(new MediaUploadedEvent(asset.getId()));
        return toView(asset, s3.presignDownload(asset.getObjectKey()));
    }

    /**
     * Read path: presigned GET URLs for every UPLOADED asset of the listing,
     * in display order — original plus thumbnail (L28). The thumbnail link
     * is null until background processing has run; clients fall back to the
     * original. Presigning is local computation — no cache, no network.
     */
    @Transactional(readOnly = true)
    public List<MediaAssetView> listByListing(UUID listingId) {
        S3MediaStorage s3 = requireStorage();
        return mediaAssetRepository
                .findByListingIdAndStatusOrderByPositionAsc(listingId, MediaAssetStatus.UPLOADED)
                .stream()
                .map(asset -> toView(asset,
                        s3.presignDownload(asset.getObjectKey()),
                        asset.getThumbObjectKey() == null
                                ? null
                                : s3.presignDownload(asset.getThumbObjectKey())))
                .toList();
    }

    /**
     * Soft-deletes the asset record and best-effort removes the storage
     * objects (original + thumbnail) — only AFTER the database delete
     * commits (CodeRabbit #241): the repository delete is just scheduled
     * until commit, so removing the object first would leave the row
     * pointing at a vanished object whenever the transaction rolls back.
     * A storage-side removal failure is logged, never fatal — bucket
     * lifecycle rules own orphans.
     */
    @Observed(name = "media.asset.delete")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public void delete(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        mediaAssetRepository.delete(asset);
        String objectKey = asset.getObjectKey();
        String thumbKey = asset.getThumbObjectKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    removeStorageObjects(s3, objectKey, thumbKey);
                }
            });
        } else {
            // No active transaction (unit tests) — remove immediately.
            removeStorageObjects(s3, objectKey, thumbKey);
        }
    }

    /**
     * L28: removes the original and — when a distinct thumbnail object
     * exists — the thumbnail. Same best-effort contract as the original.
     */
    private void removeStorageObjects(S3MediaStorage s3, String objectKey, String thumbKey) {
        removeStorageObject(s3, objectKey);
        if (thumbKey != null && !thumbKey.equals(objectKey)) {
            removeStorageObject(s3, thumbKey);
        }
    }

    private void removeStorageObject(S3MediaStorage s3, String objectKey) {
        try {
            s3.deleteObject(objectKey);
        } catch (RuntimeException ex) {
            log.warn("Storage object removal failed for key {} (bucket lifecycle will own it): {}",
                    objectKey, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public MediaAsset getById(UUID id) {
        return mediaAssetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset not found: " + id));
    }

    /**
     * L28 (feature-expansion roadmap §5): the thumbnail pipeline's core —
     * invoked by {@link MediaThumbnailListener} AFTER the confirm transaction
     * committed. Deliberately NOT {@code @PreAuthorize}-guarded: it is an
     * internal command with no caller authentication context (the async
     * listener thread) — the ownership was already enforced on the confirm
     * call that published the event.
     *
     * <p>Decision matrix (the roadmap's acceptance criteria, made explicit):
     * <ul>
     *   <li>already processed ({@code thumbObjectKey != null}) — idempotent
     *       return (a resubmission re-run stores nothing twice);</li>
     *   <li>JPEG/PNG wider than {@code thumbMaxWidth} — fetch, scale, store
     *       under the deterministic {@code {objectKey}/thumb}, pin the key;</li>
     *   <li>JPEG/PNG already within the bound — thumb = original (no
     *       duplicate object);</li>
     *   <li>any other allowlisted MIME (webp has no JDK ImageIO writer, gif
     *       would collapse to a static frame) — thumb = original, by design
     *       (roadmap acceptance 4).</li>
     * </ul>
     * A processing failure (unreadable bytes, storage error) propagates — the
     * Modulith publication goes FAILED and the documented resubmission
     * machinery retries it later (debt D3: bounded, logged, no silent drop);
     * the upload itself already succeeded and stays UPLOADED.
     */
    @Observed(name = "media.thumbnail.process")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void processThumbnail(UUID mediaId) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        if (asset.getThumbObjectKey() != null) {
            // Idempotent: a resubmission re-running the listener must not
            // re-store the object or flip the pointer.
            return;
        }
        String thumbKey = asset.getObjectKey() + "/thumb";
        if (Thumbnails.isProcessable(asset.getContentType())) {
            byte[] original = s3.getObject(asset.getObjectKey());
            try {
                byte[] scaled = Thumbnails.scaleToMaxWidth(
                        original, properties.limits().thumbMaxWidth(), asset.getContentType(),
                        properties.limits().thumbSourceMaxPixels());
                if (scaled != null) {
                    s3.putObject(thumbKey, asset.getContentType(), scaled);
                    asset.recordThumbKey(thumbKey);
                } else {
                    // Within the width bound, or over the raster budget
                    // (header-declared pixels above the configured limit —
                    // never decoded) — the original is its own thumbnail.
                    asset.recordThumbKey(asset.getObjectKey());
                }
            } catch (java.io.IOException ex) {
                // Unreadable image bytes — a processing failure by contract
                // (publication FAILED, retried; the original stays UPLOADED).
                throw new IllegalStateException(
                        "Thumbnail processing failed for media " + mediaId + ": " + ex.getMessage(), ex);
            }
        } else {
            // webp/gif (or future allowlist additions without a JDK writer):
            // the thumbnail IS the original — documented, no failure.
            asset.recordThumbKey(asset.getObjectKey());
        }
        mediaAssetRepository.save(asset);
    }

    private S3MediaStorage requireStorage() {
        S3MediaStorage s3 = storage.getIfAvailable();
        if (s3 == null) {
            throw new ServiceUnavailableException(
                    "Media storage is not configured. Set MEDIA_S3_ENDPOINT, MEDIA_S3_BUCKET, "
                            + "MEDIA_S3_ACCESS_KEY and MEDIA_S3_SECRET_KEY to enable listing media.");
        }
        return s3;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("Content type is required");
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private void validateContentType(String normalizedType) {
        if (!properties.limits().allowedContentTypes().contains(normalizedType)) {
            throw new BadRequestException(
                    "Unsupported media content type: " + normalizedType
                            + " (allowed: " + properties.limits().allowedContentTypes() + ")");
        }
    }

    private void validateSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > properties.limits().maxUploadBytes()) {
            throw new BadRequestException(
                    "Media size " + sizeBytes + " bytes is outside the allowed range (max "
                            + properties.limits().maxUploadBytes() + ")");
        }
    }

    /**
     * Same ownership rule as {@code CatalogService.verifyOwnership}: admin passes,
     * otherwise the provider record behind the listing/asset must be linked to
     * the current user.
     */
    private void verifyOwnership(UUID providerId, Authentication authentication, String denialMessage) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        // A1: the providerId of an asset/listing is a user id (V2/V32),
        // so compare against the user-owned profile via findByUserId.
        providerLookupPort.findByUserId(providerId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElseThrow(() -> new AccessDeniedException(denialMessage));
    }

    private void verifyListingOwnership(UUID providerId, Authentication authentication) {
        verifyOwnership(providerId, authentication, "You do not own this listing");
    }

    private void verifyAssetOwnership(MediaAsset asset, Authentication authentication) {
        verifyOwnership(asset.getProviderId(), authentication, "You do not own this media asset");
    }

    private String buildObjectKey(UUID listingId, String contentType) {
        String extension = EXTENSION_BY_TYPE.getOrDefault(contentType, "bin");
        return "listings/" + listingId + "/" + UUID.randomUUID() + "." + extension;
    }

    /**
     * Confirm-time view: the thumbnail has not been processed yet (the
     * listener runs AFTER_COMMIT) — {@code thumbUrl} is null by contract.
     */
    private MediaAssetView toView(MediaAsset asset, String downloadUrl) {
        return toView(asset, downloadUrl, null);
    }

    /**
     * L28: the read-side view carries both links — original plus thumbnail
     * (null until processing has run; equal URLs when thumb = original).
     */
    private MediaAssetView toView(MediaAsset asset, String downloadUrl, String thumbUrl) {
        return new MediaAssetView(
                asset.getId(),
                asset.getListingId(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getStatus().name(),
                asset.getPosition(),
                downloadUrl,
                thumbUrl,
                asset.getCreatedAt()
        );
    }

    /**
     * Response of {@link #requestUpload} — everything a client needs to upload
     * directly to storage.
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "Presigned upload response — the client PUTs its bytes to uploadUrl with the "
                    + "declared Content-Type, then calls the complete endpoint")
    public record MediaUploadView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "The persisted media asset id",
                    example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            UUID mediaId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Server-generated object key",
                    example = "listings/7c9e6679-7425-40de-944b-e07fc1f90ae7/1b2c3d4e-....jpg")
            String objectKey,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Presigned PUT URL (the TTL is urlLifetime)",
                    example = "https://bucket.r2.cloudflarestorage.com/listings/...?X-Amz-Signature=...")
            String uploadUrl,
            @io.swagger.v3.oas.annotations.media.Schema(description = "How long the presigned URL stays valid",
                    example = "PT15M")
            java.time.Duration urlLifetime) {}

    /**
     * Read/confirm response — the presigned GET URLs are freshly signed per
     * call. L28: {@code thumbUrl} is null until background processing has
     * run (fall back to {@code downloadUrl}); it equals {@code downloadUrl}
     * when the thumbnail is the original by design.
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "Media asset with presigned read links — original plus thumbnail when processed")
    public record MediaAssetView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "The media asset id",
                    example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            UUID id,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The listing this asset belongs to",
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            UUID listingId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Image content type",
                    example = "image/jpeg")
            String contentType,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Object size in bytes",
                    example = "418381")
            long sizeBytes,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Asset lifecycle status",
                    example = "UPLOADED")
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Display order within the listing (1-based)",
                    example = "1")
            int position,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Presigned GET URL of the original object",
                    example = "https://bucket.r2.cloudflarestorage.com/listings/...?X-Amz-Signature=...")
            String downloadUrl,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Presigned GET URL of the thumbnail — "
                    + "null until processing completes (L28), equal to downloadUrl when the "
                    + "thumbnail is the original by design",
                    nullable = true,
                    example = "https://bucket.r2.cloudflarestorage.com/listings/.../thumb?X-Amz-Signature=...")
            String thumbUrl,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Creation timestamp",
                    example = "2026-10-01T12:00:00Z")
            java.time.Instant createdAt) {}
}
