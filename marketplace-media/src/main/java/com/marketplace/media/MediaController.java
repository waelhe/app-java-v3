package com.marketplace.media;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class MediaController {

    private final MediaService mediaService;
    private final CurrentUserProvider currentUserProvider;

    public MediaController(MediaService mediaService, CurrentUserProvider currentUserProvider) {
        this.mediaService = mediaService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Issue a presigned upload URL for a new photo. The client PUTs the
     * bytes directly to storage with the returned URL (and the declared
     * Content-Type), then calls the complete endpoint.
     *
     * <p>L29 (feature-expansion roadmap §5, Week 4): the upload request is one
     * of the three high-impact public write endpoints covered by an independent
     * named rate-limiter instance (fail-fast 429 RL-001).
     *
     * <p>L48 + A-17 (compliance plan C.7 — the M1 store root): the single
     * media-upload channel serves the THREE targets of the generalized media
     * line — exactly one of {@code listingId} (the provider flow, PROVIDER
     * role + listing ownership in the service), {@code postId} (the member
     * flow, the post's author alone) or {@code productId} (the store flow,
     * the product's owning provider) must be present; the type gate below
     * answers the house 400 BEFORE any service call, the same
     * {@code parseCategory} discipline the community controller pins. The
     * complete/delete endpoints are target-agnostic — the asset row carries
     * its own target and the ownership gate reads it.
     */
    @PostMapping("/media/uploads")
    @RateLimiter(name = "mediaUpload")
    @Operation(summary = "Request a presigned media upload",
            description = "Returns a presigned PUT URL the client uploads bytes to directly. The "
                    + "object key is server-generated; the declared content type is pinned into the "
                    + "signature. Call the complete endpoint after the upload. Exactly one target — "
                    + "listingId (the listing flow), postId (the neighborhood post flow, L48) or "
                    + "productId (the store product flow, A-17/C.7) — must be present; carrying "
                    + "more than one or none answers 400 before anything is signed.")
    public ResponseEntity<MediaService.MediaUploadView> requestUpload(
            @Valid @RequestBody RequestUploadRequest request, Authentication authentication) {
        int targets = (request.listingId() != null ? 1 : 0)
                + (request.postId() != null ? 1 : 0)
                + (request.productId() != null ? 1 : 0);
        if (targets != 1) {
            throw new BadRequestException(
                    "Exactly one target is required — listingId, postId or productId (got "
                            + (targets == 0 ? "none" : "more than one") + ")");
        }
        MediaService.MediaUploadView view = request.postId() != null
                ? mediaService.requestPostUpload(
                        request.postId(), request.contentType(), request.sizeBytes(), authentication)
                : request.productId() != null
                        ? mediaService.requestProductUpload(
                                request.productId(), request.contentType(), request.sizeBytes(), authentication)
                        : mediaService.requestUpload(
                                request.listingId(), request.contentType(), request.sizeBytes(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Confirm the upload happened (verified server-side via HeadObject) and
     * make the asset visible on the listing.
     */
    @PostMapping("/media/{id}/complete")
    @Operation(summary = "Confirm an uploaded media asset",
            description = "Server-side verification (HeadObject) that the object exists with exactly "
                    + "the declared type and size; the asset becomes visible on the listing.")
    public ResponseEntity<MediaService.MediaAssetView> confirmUpload(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(mediaService.confirmUpload(id, authentication));
    }

    /**
     * Presigned read URLs for every uploaded asset of the listing, in display
     * order. Public read (S5, comprehensive repair plan §10/2.2) — the same
     * visibility the listing endpoints grant the listing itself: a guest
     * reading the public detail page sees its photos too. The response
     * carries only time-limited presigned GET URLs for UPLOADED assets.
     *
     * <p><b>R5 (comprehensive-review-ar-fix plan §4/R5 — media privacy):</b>
     * the read is gated by the listing's publication state — ACTIVE
     * listings serve everyone (anonymous included); DRAFT/PAUSED/ARCHIVED
     * listings answer the public listing surface's own 404 to everyone but
     * the owning provider (and admins). The optional {@code Authentication}
     * parameter is the L34 optional-identity seam — the same shape
     * {@code POST /api/v1/listings/{id}/leads} (lead capture) carries for
     * public surfaces that accept both anonymous and authenticated
     * callers; an invalid presented token never reaches here (the
     * resource-server filter rejects it with 401 first).</p>
     */
    @GetMapping("/media/listings/{listingId}")
    @Operation(summary = "List a listing's media",
            description = "Every UPLOADED asset in display order, each with a freshly presigned "
                    + "GET URL. Public read for ACTIVE listings — same visibility as the listing "
                    + "endpoints; non-public listings (draft/paused/archived) answer 404 to "
                    + "everyone but the owning provider.")
    public ResponseEntity<List<MediaService.MediaAssetView>> listByListing(
            @PathVariable UUID listingId, Authentication authentication) {
        return ResponseEntity.ok(mediaService.listByListing(listingId, authentication));
    }

    /**
     * A-17 (compliance plan C.7 — the M1 store root): the product-target
     * read — every UPLOADED asset of the store product in display order,
     * each with a freshly presigned GET URL. The M1 root has no public
     * storefront surface (that arrives with the M2 wave, C.8), so the read
     * is the owning provider's own (plus admins); a stranger's read
     * answers the honest 404 — the R5 privacy posture for non-public
     * targets (the same shape the non-published listing read carries).
     */
    @GetMapping("/media/products/{productId}")
    @Operation(summary = "List a store product's media (M1 — the owner's read)",
            description = "Every UPLOADED asset in display order, each with a freshly presigned "
                    + "GET URL. The owning provider (and admins) read; a stranger's read answers "
                    + "the honest 404 — the public storefront surfaces arrive with the M2 wave.")
    public ResponseEntity<List<MediaService.MediaAssetView>> listByProduct(
            @PathVariable UUID productId, Authentication authentication) {
        return ResponseEntity.ok(mediaService.listByProduct(productId, authentication));
    }

    @DeleteMapping("/media/{id}")
    @Operation(summary = "Delete a media asset", description = "Soft-deletes the asset and removes "
            + "the storage object best-effort after commit.")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        mediaService.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * Upload declaration contract. {@code sizeBytes} carries only shape
     * constraints ({@code @NotNull @Min(1)} — a declared file must be a
     * positive number). The maximum upload size is a configurable business
     * limit owned by {@link MediaProperties.Limits#maxUploadBytes()} (bound
     * from {@code marketplace.media.limits.max-upload-bytes},
     * {@code MEDIA_MAX_UPLOAD_BYTES} in env) and enforced by
     * {@link MediaService#requestUpload} behind the official
     * {@code @ConfigurationProperties} channel — never a hard-coded constant,
     * per the Spring Boot externalized-configuration model.
     *
     * <p>L48 + A-17: exactly one target — {@code listingId} (optional since
     * L48; every pre-L48 request still carries it, unchanged), {@code postId}
     * or {@code productId}. The exactly-one rule is the controller's type
     * gate above, answering 400 before any service call.
     */
    @Schema(description = "Upload declaration: exactly one target (listingId, postId or productId), the declared "
            + "content type and size — verified server-side at confirm time")
    public record RequestUploadRequest(
            @Schema(description = "The listing the photo belongs to (the listing flow's target)",
                    nullable = true,
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            UUID listingId,
            @Schema(description = "The neighborhood post the photo belongs to (the post flow's "
                    + "target — L48; the post's author alone may upload)",
                    nullable = true,
                    example = "9c8b7a65-4321-4fed-ba98-76543210fedc")
            UUID postId,
            @Schema(description = "The store product the photo belongs to (the product flow's "
                    + "target — A-17/C.7; the product's owning provider alone may upload)",
                    nullable = true,
                    example = "5e4d3c2b-1a09-876f-ed54-321098765abc")
            UUID productId,
            @Schema(description = "Declared image content type (from the server allowlist)",
                    example = "image/jpeg")
            @NotBlank String contentType,
            @Schema(description = "Declared file size in bytes (must match the uploaded object exactly)",
                    example = "418381", minimum = "1")
            @NotNull @Min(1) Long sizeBytes
    ) {
    }
}
