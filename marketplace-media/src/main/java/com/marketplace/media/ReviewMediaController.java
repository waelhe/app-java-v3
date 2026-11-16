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

/**
 * W1 (§4.4): the review-media surface — the {@code MediaController} twin
 * scoped to a review (the listing twin stays untouched). The upload
 * request rides the same {@code mediaUpload} rate-limiter budget: an
 * upload is an upload.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ReviewMediaController {

    private final ReviewMediaService reviewMediaService;
    private final CurrentUserProvider currentUserProvider;

    public ReviewMediaController(ReviewMediaService reviewMediaService,
                                 CurrentUserProvider currentUserProvider) {
        this.reviewMediaService = reviewMediaService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Issue a presigned upload URL for a new photo of the review (the review's author only). */
    @PostMapping("/media/reviews/uploads")
    @RateLimiter(name = "mediaUpload")
    @Operation(summary = "Request a presigned review-photo upload",
            description = "Returns a presigned PUT URL the client uploads bytes to directly. The "
                    + "object key is server-generated; the declared content type is pinned into "
                    + "the signature. Call the complete endpoint after the upload. Only the "
                    + "review's author may request the upload.")
    public ResponseEntity<ReviewMediaService.ReviewMediaUploadView> requestUpload(
            @Valid @RequestBody RequestReviewUploadRequest request, Authentication authentication) {
        ReviewMediaService.ReviewMediaUploadView view = reviewMediaService.requestUpload(
                request.reviewId(), request.contentType(), request.sizeBytes(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** Confirm the upload happened (verified server-side via HeadObject). */
    @PostMapping("/media/reviews/{id}/complete")
    @Operation(summary = "Confirm an uploaded review-photo",
            description = "Server-side verification (HeadObject) that the object exists with exactly "
                    + "the declared type and size; the asset becomes readable on the review.")
    public ResponseEntity<ReviewMediaService.ReviewMediaView> confirmUpload(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(reviewMediaService.confirmUpload(id, authentication));
    }

    /**
     * Presigned read URLs for every uploaded asset of the review, in
     * display order. Public for a PUBLISHED review (the same visibility
     * the review endpoints grant it); for any other moderation state the
     * author and admins only.
     */
    @GetMapping("/media/reviews/by-review/{reviewId}")
    @Operation(summary = "List a review's photos",
            description = "Every UPLOADED asset in display order, each with a freshly presigned "
                    + "GET URL. Public when the review is published; otherwise its author (or an "
                    + "admin) only.")
    public ResponseEntity<List<ReviewMediaService.ReviewMediaView>> listByReview(
            @PathVariable UUID reviewId, Authentication authentication) {
        return ResponseEntity.ok(reviewMediaService.listByReview(reviewId, authentication));
    }

    @DeleteMapping("/media/reviews/{id}")
    @Operation(summary = "Delete a review-photo", description = "Soft-deletes the asset and removes "
            + "the storage object best-effort after commit. The review's author (or an admin).")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        reviewMediaService.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }

    /** Upload declaration contract — the same shape and bounds as the listing twin. */
    @Schema(description = "Review upload declaration: the review, the declared content type and size")
    public record RequestReviewUploadRequest(
            @Schema(description = "The review the photo belongs to",
                    example = "0d3b3f7e-1f2a-4c3b-9c2d-5e6f7a8b9c0d")
            @NotNull UUID reviewId,
            @Schema(description = "Declared image content type (from the server allowlist)",
                    example = "image/jpeg")
            @NotBlank String contentType,
            @Schema(description = "Declared file size in bytes (must match the uploaded object exactly)",
                    example = "418381", minimum = "1")
            @NotNull @Min(1) Long sizeBytes
    ) {
    }
}
