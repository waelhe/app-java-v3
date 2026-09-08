package com.marketplace.media;

import com.marketplace.shared.api.ApiConstants;
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
     * Issue a presigned upload URL for a new listing photo. The client PUTs the
     * bytes directly to storage with the returned URL (and the declared
     * Content-Type), then calls the complete endpoint.
     *
     * <p>L29 (feature-expansion roadmap §5, Week 4): the upload request is one
     * of the three high-impact public write endpoints covered by an independent
     * named rate-limiter instance (fail-fast 429 RL-001).
     */
    @PostMapping("/media/uploads")
    @RateLimiter(name = "mediaUpload")
    @Operation(summary = "Request a presigned media upload",
            description = "Returns a presigned PUT URL the client uploads bytes to directly. The "
                    + "object key is server-generated; the declared content type is pinned into the "
                    + "signature. Call the complete endpoint after the upload.")
    public ResponseEntity<MediaService.MediaUploadView> requestUpload(
            @Valid @RequestBody RequestUploadRequest request, Authentication authentication) {
        MediaService.MediaUploadView view = mediaService.requestUpload(
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
     * order. Authenticated read — same visibility as the listing endpoints.
     */
    @GetMapping("/media/listings/{listingId}")
    @Operation(summary = "List a listing's media",
            description = "Every UPLOADED asset in display order, each with a freshly presigned "
                    + "GET URL.")
    public ResponseEntity<List<MediaService.MediaAssetView>> listByListing(
            @PathVariable UUID listingId) {
        return ResponseEntity.ok(mediaService.listByListing(listingId));
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
     */
    @Schema(description = "Upload declaration: the listing, the declared content type and size — "
            + "verified server-side at confirm time")
    public record RequestUploadRequest(
            @Schema(description = "The listing the photo belongs to",
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @NotNull UUID listingId,
            @Schema(description = "Declared image content type (from the server allowlist)",
                    example = "image/jpeg")
            @NotBlank String contentType,
            @Schema(description = "Declared file size in bytes (must match the uploaded object exactly)",
                    example = "418381", minimum = "1")
            @NotNull @Min(1) Long sizeBytes
    ) {
    }
}
