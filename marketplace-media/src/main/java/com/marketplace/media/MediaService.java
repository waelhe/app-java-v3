package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ListingPublicStatePort;
import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.MediaUploadedEvent;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ProductLookupPort;
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

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Media business logic — the generalized media line (L48 widened the
 * listing-only original, roadmap item B1 / G-PROD-1): issue a presigned
 * upload URL bound to a server-generated object key, verify the object
 * after upload, expose presigned read URLs — for a provider LISTING or a
 * neighborhood POST target (the community plan's D-C3 decision).
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

    // The upload rules (storage gate, content-type allowlist, size bound,
    // server-generated key shape) live in MediaUploadRules — now shared
    // with the review-media service (W1): one implementation, so the two
    // surfaces cannot drift apart silently.

    private final MediaAssetRepository mediaAssetRepository;
    private final ObjectProvider<S3MediaStorage> storage;
    private final MediaProperties properties;
    private final ListingPriceProvider listingPriceProvider;
    private final ListingPublicStatePort listingPublicStatePort;
    private final ProviderLookupPort providerLookupPort;
    private final PostLookupPort postLookupPort;
    private final ProductLookupPort productLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaThumbnailMetrics thumbnailMetrics;

    public MediaService(MediaAssetRepository mediaAssetRepository,
                        ObjectProvider<S3MediaStorage> storage,
                        MediaProperties properties,
                        ListingPriceProvider listingPriceProvider,
                        ListingPublicStatePort listingPublicStatePort,
                        ProviderLookupPort providerLookupPort,
                        PostLookupPort postLookupPort,
                        ProductLookupPort productLookupPort,
                        CurrentUserProvider currentUserProvider,
                        ApplicationEventPublisher eventPublisher,
                        MediaThumbnailMetrics thumbnailMetrics) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storage = storage;
        this.properties = properties;
        this.listingPriceProvider = listingPriceProvider;
        this.listingPublicStatePort = listingPublicStatePort;
        this.providerLookupPort = providerLookupPort;
        this.postLookupPort = postLookupPort;
        this.productLookupPort = productLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.thumbnailMetrics = thumbnailMetrics;
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
                // The highest allocated live position plus one — a soft deletion
                // never re-opens a slot a remaining row still holds (greptile W1
                // r10, adopted from the root; same root fix as the review channel).
                sizeBytes, mediaAssetRepository.findMaxPositionByListingId(listingId) + 1));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new MediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * L48 (gap #2 — post images): issues a presigned PUT URL for a new asset
     * of the given neighborhood POST — the second target of the generalized
     * media line (the D-C3 decision). Same discipline as the listing flow:
     * type allowlist and size cap validated BEFORE anything is signed, the
     * target resolved through its port (the honest 404 for an unknown,
     * hidden or deleted post — the comment gate's visiblePost behavior at
     * the seam), and the caller verified to be the AUTHOR (the post flow's
     * ownership: {@code providerId} carries the author's user id directly —
     * a member author need not hold a provider profile, so the provider-
     * profile resolution of the listing flow does not apply here).
     *
     * <p>Role gate: {@code isAuthenticated()} — any authenticated member,
     * because posting is the member domain (the feed's own gate, not the
     * provider domain's PROVIDER role). The authorship check below is the
     * real authority; there is deliberately NO admin bypass on THIS method —
     * an admin uploading onto a member's post would attribute another's
     * photo to that member, which the listing flow never allows either
     * (an admin never upload-creates; the admin path exists on DELETE
     * alone, where it moderates).
     */
    @Observed(name = "media.upload.request.post")
    @PreAuthorize("isAuthenticated()")
    public MediaUploadView requestPostUpload(UUID postId, String contentType,
                                             long sizeBytes, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        String normalizedType = normalizeContentType(contentType);
        validateContentType(normalizedType);
        validateSize(sizeBytes);

        PostLookupPort.PostInfo post = postLookupPort.getPostInfo(postId);
        verifyPostAuthorship(post.authorId(), authentication);
        verifyPostWriteRight(post);

        // Display-position allocation must be atomic per post (CodeRabbit
        // #241, the listing flow's exact discipline): the advisory
        // transaction lock below is held until the surrounding transaction
        // commits, so per-post allocations are serialized in the database.
        mediaAssetRepository.lockPostPositionAllocation(postId.toString());

        String objectKey = buildPostObjectKey(postId, normalizedType);
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.createForPost(
                postId, post.authorId(), objectKey, normalizedType,
                // Greptile W1 r10 (adopted): max-based allocation — the post twin
                // of the listing fix above.
                sizeBytes, mediaAssetRepository.findMaxPositionByPostId(postId) + 1));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new MediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * A-17 (compliance plan C.7 — the M1 store root): issues a presigned
     * PUT URL for a new asset of the given store PRODUCT — the media
     * line's third target, the {@code requestUpload} (listing) gate shape
     * on the product seam: PROVIDER role (the method security below), the
     * product's owning provider resolved through {@code ProductLookupPort}
     * (existence answers the seam's own 404 — attaching to the absent is
     * nonsense), and plain user-id ownership (the M1 root has no
     * publication state, so there is no visibility gate to re-check — the
     * owner is always writable, exactly like the listing flow's own
     * always-writable provider). Position allocation is the same advisory
     * transaction lock discipline (CodeRabbit #241), serialized per
     * PRODUCT id in the shared key space.
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public MediaUploadView requestProductUpload(UUID productId, String contentType,
                                                 long sizeBytes, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        String normalizedType = normalizeContentType(contentType);
        validateContentType(normalizedType);
        validateSize(sizeBytes);

        ProductLookupPort.ProductInfo product = productLookupPort.getProductInfo(productId);
        verifyListingOwnership(product.providerId(), authentication);

        mediaAssetRepository.lockProductPositionAllocation(productId.toString());

        String objectKey = buildProductObjectKey(productId, normalizedType);
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.createForProduct(
                productId, product.providerId(), objectKey, normalizedType,
                sizeBytes, mediaAssetRepository.findMaxPositionByProductId(productId) + 1));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new MediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * A-17 (C.7): the owner-gated product read — every UPLOADED asset in
     * display order, each with a freshly presigned GET URL. The M1 root
     * has no public storefront surface (that arrives with the M2 wave,
     * C.8), so the read is the owning provider's own (plus admins, the
     * {@code verifyListingOwnership} admin bypass); a stranger's read
     * answers the honest 404 — the R5 privacy posture for non-public
     * targets.
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public List<MediaAssetView> listByProduct(UUID productId, Authentication authentication) {
        ProductLookupPort.ProductInfo product = productLookupPort.getProductInfo(productId);
        verifyListingOwnership(product.providerId(), authentication);
        // The honest degradation (listByListing's own rule): query FIRST,
        // require storage only when rows exist — a photo-less product's read
        // never touches storage.
        List<MediaAsset> assets = mediaAssetRepository
                .findByProductIdAndStatusOrderByPositionAsc(productId, MediaAssetStatus.UPLOADED);
        if (assets.isEmpty()) {
            return List.of();
        }
        S3MediaStorage s3 = requireStorage();
        return assets
                .stream()
                .map(asset -> toView(asset,
                        s3.presignDownload(asset.getObjectKey()),
                        asset.getThumbObjectKey() == null
                                ? null
                                : s3.presignDownload(asset.getThumbObjectKey())))
                .toList();
    }

    /**
     * Confirms an upload: verifies via HeadObject that the object exists with
     * exactly the declared type and size, then moves the asset to UPLOADED
     * and publishes {@link MediaUploadedEvent} (L28) — the thumbnail pipeline
     * listens AFTER_COMMIT, so the event only exists once this state is
     * durable. A failed verification leaves the asset PENDING — confirmable
     * again.
     *
     * <p><b>POST-target assets re-gate at confirm (the #484 review
     * round):</b> the request path's authorization is a snapshot — the
     * author's community-write right and the post's visibility can both
     * change between the presign and the confirm (a membership rejected, a
     * post moderated-hidden). The listing flow has no equivalent because a
     * listing has no author-membership concept; the post target does, so
     * the confirm resolves the post fresh through the same port and re-runs
     * both gates — a hidden post answers the honest 404 (attaching to the
     * dead is nonsense the seam already refuses at request time), and a
     * rejected author answers the same 403 the publish/comment/react
     * commands answer. The asset stays PENDING on either refusal —
     * confirmable again only through an honest path.
     */
    @Observed(name = "media.upload.confirm")
    @PreAuthorize("isAuthenticated()")
    public MediaAssetView confirmUpload(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);
        if (asset.getPostId() != null && !currentUserProvider.isAdmin(authentication)) {
            PostLookupPort.PostInfo post = postLookupPort.getPostInfo(asset.getPostId());
            verifyPostAuthorship(post.authorId(), authentication);
            verifyPostWriteRight(post);
        }

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
     *
     * <p><b>R5 (comprehensive-review-ar-fix plan §4/R5 — media privacy):</b>
     * the links are gated by the listing's PUBLICATION STATE — the measured
     * defect had a PAUSED/ARCHIVED listing's photos fully readable by any
     * anonymous caller. The gate mirrors the public listing surface's own
     * appearance: a listing that is not on the public surface (DRAFT/PAUSED/
     * ARCHIVED — {@code ListingPublicStatePort}, implemented by the catalog
     * owner with {@code getActiveById}'s exact filter) answers the SAME 404
     * shape {@code GET /api/v1/listings/{id}} answers, for the anonymous
     * caller and the foreign authenticated caller alike — existence is
     * not confirmed to non-owners. The OWNING provider keeps reading his
     * draft/paused listing's media through this same read point (the L34
     * optional-identity seam: a valid JWT on a public surface resolves the
     * caller, no token stays anonymous) under the unit's ownership
     * convention — admin passes, the provider record behind the listing
     * must be linked to the current user. Zero new module code: one port in
     * shared/api, the data owner's implementation, this gate.</p>
     */
    @Transactional(readOnly = true)
    public List<MediaAssetView> listByListing(UUID listingId, Authentication authentication) {
        // The R5 privacy gate first (main's wave 4): the listing's PUBLICATION
        // STATE decides readability — a non-published listing (DRAFT/PAUSED/
        // ARCHIVED) answers the public surface's own 404 for the anonymous and
        // the foreign caller alike; the OWNING provider (and admin) keep reading.
        requireReadableListing(listingId, authentication);
        // THEN the honest degradation (the 2026-10-01 measured fix): query
        // FIRST, require storage only when rows exist — a photo-less listing's
        // read never touches storage, so an unconfigured channel degrades to
        // the honest empty gallery instead of failing the whole surface (the
        // empty-page-costs-nothing rule). Production (storage configured) is
        // byte-identical: rows exist, presigning runs.
        List<MediaAsset> assets = mediaAssetRepository
                .findByListingIdAndStatusOrderByPositionAsc(listingId, MediaAssetStatus.UPLOADED);
        if (assets.isEmpty()) {
            return List.of();
        }
        S3MediaStorage s3 = requireStorage();
        return assets
                .stream()
                .map(asset -> toView(asset,
                        s3.presignDownload(asset.getObjectKey()),
                        asset.getThumbObjectKey() == null
                                ? null
                                : s3.presignDownload(asset.getThumbObjectKey())))
                .toList();
    }

    /**
     * R5 gate: public listing ⇒ everyone (anonymous included); otherwise
     * only the owning provider (or an admin) — anyone else gets the public
     * listing surface's own 404 shape.
     */
    private void requireReadableListing(UUID listingId, Authentication authentication) {
        if (listingPublicStatePort.isPubliclyVisible(listingId)) {
            return;
        }
        // Not on the public surface: resolve the listing (an unknown id
        // answers the public path's own 404 — getListingInfo routes through
        // getById) and demand the owner-viewer identity.
        ListingPriceProvider.ListingInfo listing = listingPriceProvider.getListingInfo(listingId);
        UUID userId = currentUserProvider.tryGetCurrentUserId(authentication).orElse(null);
        if (userId != null && currentUserProvider.isAdmin(authentication)) {
            return;
        }
        if (userId == null || !isListingOwner(listing.providerId(), userId)) {
            throw new ResourceNotFoundException("Listing", listingId);
        }
    }

    /**
     * The owner test of {@link #verifyOwnership} as a predicate — the same
     * A1 resolution (the listing's providerId is a user id; the user-owned
     * profile behind it must match the caller).
     */
    private boolean isListingOwner(UUID providerId, UUID userId) {
        return providerLookupPort.findByUserId(providerId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(userId))
                .isPresent();
    }

    /**
     * L48: the feed's one grouped media read for the community module —
     * delegates the query to {@link MediaLookupAdapter} through the port
     * (this method exists on the SERVICE only because presigning lives
     * here; the adapter calls it). Every UPLOADED asset of the given posts
     * in display order, original plus L28 thumbnail presigned GETs.
     */
    @Transactional(readOnly = true)
    public List<MediaLookupPort.PostMediaEntry> listByPostIds(Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        // Query FIRST, require storage only when presigning is actually
        // needed (the 2026-10-01 CI round's measured fix): a feed page
        // whose posts carry NO photos never touches storage — the
        // "empty page costs nothing" rule generalized to "no rows, no
        // storage" — so every text-only feed read works on an
        // unconfigured channel instead of failing the whole surface with
        // a 503. Production (storage configured) is byte-identical.
        List<MediaAsset> assets = mediaAssetRepository
                .findByPostIdInAndStatusOrderByPostIdAscPositionAsc(postIds, MediaAssetStatus.UPLOADED);
        if (assets.isEmpty()) {
            return List.of();
        }
        S3MediaStorage s3 = requireStorage();
        return assets
                .stream()
                .map(asset -> new MediaLookupPort.PostMediaEntry(
                        asset.getPostId(),
                        asset.getId(),
                        s3.presignDownload(asset.getObjectKey()),
                        asset.getThumbObjectKey() == null
                                ? null
                                : s3.presignDownload(asset.getThumbObjectKey()),
                        asset.getContentType(),
                        asset.getPosition()))
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
    @PreAuthorize("isAuthenticated()")
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
     *
     * <p>D3 closure (roadmap §8): every failure is counted by its measured
     * source BEFORE rethrowing ({@code marketplace.media.thumbnail.failure}
     * with a {@code reason} tag — fetch/decode/encode/store) so operations
     * can see what the retry machinery is working on. Counting never changes
     * the failure semantics: the exception always propagates.
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
            byte[] original;
            try {
                original = s3.getObject(asset.getObjectKey());
            } catch (RuntimeException ex) {
                // D3: count the fetch-stage failure, then let it propagate —
                // the publication goes FAILED and the framework-owned
                // resubmission machinery owns the retry. Never swallowed.
                thumbnailMetrics.incrementFailure(MediaThumbnailMetrics.FailureReason.FETCH);
                throw ex;
            }
            try {
                byte[] scaled = Thumbnails.scaleToMaxWidth(
                        original, properties.limits().thumbMaxWidth(), asset.getContentType(),
                        properties.limits().thumbSourceMaxPixels());
                if (scaled != null) {
                    storeThumbnail(s3, thumbKey, asset.getContentType(), scaled);
                    asset.recordThumbKey(thumbKey);
                } else {
                    // Within the width bound, or over the raster budget
                    // (header-declared pixels above the configured limit —
                    // never decoded) — the original is its own thumbnail.
                    asset.recordThumbKey(asset.getObjectKey());
                }
            } catch (ThumbnailEncodingException ex) {
                // Encode stage (the write half of the image math) — counted
                // before the rethrow; the ISE wrap keeps the pipeline's
                // external failure contract exactly as it was.
                thumbnailMetrics.incrementFailure(MediaThumbnailMetrics.FailureReason.ENCODE);
                throw new IllegalStateException(
                        "Thumbnail processing failed for media " + mediaId + ": " + ex.getMessage(), ex);
            } catch (IOException ex) {
                // Decode stage (the read half of the image math) — unreadable
                // image bytes. A processing failure by contract (publication
                // FAILED, retried; the original stays UPLOADED).
                thumbnailMetrics.incrementFailure(MediaThumbnailMetrics.FailureReason.DECODE);
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

    /**
     * D3: the thumbnail store half — counted separately from the fetch half
     * so the failure counter tells operations which side of the storage
     * channel failed. Same never-swallow rule: count, then rethrow.
     */
    private void storeThumbnail(S3MediaStorage s3, String thumbKey, String contentType, byte[] scaled) {
        try {
            s3.putObject(thumbKey, contentType, scaled);
        } catch (RuntimeException ex) {
            thumbnailMetrics.incrementFailure(MediaThumbnailMetrics.FailureReason.STORE);
            throw ex;
        }
    }

    private S3MediaStorage requireStorage() {
        return MediaUploadRules.requireStorage(storage);
    }

    private String normalizeContentType(String contentType) {
        return MediaUploadRules.normalizeContentType(contentType);
    }

    private void validateContentType(String normalizedType) {
        MediaUploadRules.validateContentType(properties, normalizedType);
    }

    private void validateSize(long sizeBytes) {
        MediaUploadRules.validateSize(properties, sizeBytes);
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

    /**
     * L48: the post flow's ownership — the caller must BE the author. A
     * direct user-id comparison ({@code providerId} on a POST row IS the
     * author's user id, the A1 fact): the provider-profile resolution of
     * the listing flow does not apply, because a member author need not
     * hold a provider profile at all. No admin bypass here by design — see
     * {@link #requestPostUpload(UUID, String, long, Authentication)}.
     */
    private void verifyPostAuthorship(UUID authorId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!authorId.equals(currentUserId)) {
            throw new AccessDeniedException("You are not the author of this post");
        }
    }

    /**
     * L48 + the #484 review round: the post target's community-write gate —
     * the SAME D-N3 verdict the post service's own publish/comment/react
     * commands enforce, carried through the port's carrier leg (the
     * membership domain computes it; this module never sees a membership).
     * A REJECTED membership (or an absent one — the author left) answers
     * the explicit 403, so a member rejected after publishing cannot keep
     * attaching photos to their still-visible post: the reaction/comment
     * 403 and the photo 403 are one policy, not two.
     */
    private void verifyPostWriteRight(PostLookupPort.PostInfo post) {
        if (!post.authorMayWriteCommunity()) {
            throw new AccessDeniedException(
                    "Rejected neighborhood verification cannot publish, comment, or recommend");
        }
    }

    /**
     * L48: the confirm/delete ownership gate became target-aware — the
     * asset's own {@code ownerKind} selects the rule. LISTING rows keep the
     * provider-profile resolution verbatim (admin passes, otherwise the
     * provider record behind the asset must be linked to the caller); POST
     * rows compare the author's user id directly, with the admin pass kept
     * so the moderation surface (asset DELETE) still works on both targets
     * — an admin confirming or deleting a member's photo is moderation,
     * never authorship.
     */
    private void verifyAssetOwnership(MediaAsset asset, Authentication authentication) {
        if (asset.getOwnerKind() == MediaOwnerKind.POST) {
            if (currentUserProvider.isAdmin(authentication)) {
                return;
            }
            UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
            if (currentUserId != null && currentUserId.equals(asset.getProviderId())) {
                return;
            }
            throw new AccessDeniedException("You do not own this media asset");
        }
        verifyOwnership(asset.getProviderId(), authentication, "You do not own this media asset");
    }

    private String buildObjectKey(UUID listingId, String contentType) {
        return MediaUploadRules.buildObjectKey("listings", listingId, contentType);
    }

    /**
     * L48: the post target's own key namespace — {@code posts/{postId}/…},
     * the sibling of the listing prefix. Same construction discipline:
     * server-generated UUIDs only, no client input ever reaches the key.
     */
    private String buildPostObjectKey(UUID postId, String contentType) {
        // The reconciliation merge: the branch moved key construction into
        // MediaUploadRules.buildObjectKey (one discipline, one place); this
        // main-era twin keeps the same posts/ namespace through the helper.
        return MediaUploadRules.buildObjectKey("posts", postId, contentType);
    }

    /** A-17 (C.7): the product target's own namespace, the same one-place helper. */
    private String buildProductObjectKey(UUID productId, String contentType) {
        return MediaUploadRules.buildObjectKey("products", productId, contentType);
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
                asset.getPostId(),
                asset.getProductId(),
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
     * when the thumbnail is the original by design. L48: exactly one of
     * {@code listingId}/{@code postId} is non-null — the asset's target
     * (the V76 exactly-one-target invariant surfaced on the read model).
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "Media asset with presigned read links — original plus thumbnail when processed")
    public record MediaAssetView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "The media asset id",
                    example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            UUID id,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The listing this asset belongs to "
                    + "(null for a post-targeted asset — L48)",
                    nullable = true,
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            UUID listingId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The neighborhood post this asset "
                    + "belongs to (null for a listing-targeted asset — L48)",
                    nullable = true,
                    example = "9c8b7a65-4321-4fed-ba98-76543210fedc")
            UUID postId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The store product the asset belongs to "
                    + "(the A-17 product target — null for listing and post assets)",
                    nullable = true,
                    example = "5e4d3c2b-1a09-876f-ed54-321098765abc")
            UUID productId,
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
