package com.marketplace.reviews;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.REVIEWS, version = "1.0")
public class ReviewsController {

    private final ReviewsService reviewsService;
    private final CurrentUserProvider currentUserProvider;
    private final ReviewMapper reviewMapper;

    public ReviewsController(ReviewsService reviewsService, CurrentUserProvider currentUserProvider, ReviewMapper reviewMapper) {
        this.reviewsService = reviewsService;
        this.currentUserProvider = currentUserProvider;
        this.reviewMapper = reviewMapper;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one review", description = "A single published review with the "
            + "provider reply when one exists.")
    public ResponseEntity<ReviewResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewMapper.toResponse(reviewsService.getById(id)));
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List a provider's reviews", description = "Paginated public reviews of a "
            + "provider, newest first.")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByProvider(providerId, pageable).map(reviewMapper::toResponse)));
    }

    @GetMapping("/reviewer/{reviewerId}")
    @Operation(summary = "List a reviewer's reviews", description = "Paginated reviews written by "
            + "one consumer (public profile surface).")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByReviewer(
            @PathVariable UUID reviewerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByReviewer(reviewerId, pageable).map(reviewMapper::toResponse)));
    }

    /**
     * L29 (feature-expansion roadmap §5, Week 4): the review write is one of
     * the three high-impact public write endpoints covered by an independent
     * named rate-limiter instance (fail-fast 429 RL-001).
     */
    @PostMapping
    @RateLimiter(name = "reviewCreate")
    @Operation(summary = "Create a review",
            description = "One review per completed booking, by the consumer who booked. The stored "
                    + "provider rating average updates asynchronously.")
    public ResponseEntity<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest request,
                                                 Authentication authentication) {
        UUID reviewerId = currentUserProvider.getCurrentUserId(authentication);
        Review review = reviewsService.create(
                request.bookingId(), reviewerId,
                request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewMapper.toResponse(review));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update my review", description = "The original reviewer may edit the "
            + "rating/comment; the provider average recomputes.")
    public ResponseEntity<ReviewResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateReviewRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.ok(reviewMapper.toResponse(reviewsService.update(id, request.rating(), request.comment(), authentication)));
    }

    /**
     * L21 (roadmap §5): the provider side of the two-way review — one reply
     * per review, owned exclusively by the reviewed provider.
     */
    @PostMapping("/{id}/reply")
    @Operation(summary = "Reply to a review (provider)", description = "One public reply per review, "
            + "owned by the reviewed provider.")
    public ResponseEntity<ReviewResponse> reply(@PathVariable UUID id,
                                                 @Valid @RequestBody ReplyRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.ok(reviewMapper.toResponse(
                reviewsService.reply(id, request.reply(), authentication)));
    }

    @Schema(description = "Review request: the completed booking to review plus rating and comment")
    public record CreateReviewRequest(
            @Schema(description = "The completed booking this review is about",
                    example = "0d3b3f7e-1f2a-4c3b-9c2d-5e6f7a8b9c0d")
            @NotNull UUID bookingId,
            @Schema(description = "Rating from 1 (worst) to 5 (best)", example = "5", minimum = "1",
                    maximum = "5")
            @NotNull @Min(1) @Max(5) Integer rating,
            @Schema(description = "Optional public comment", example = "Spotless place, host replied "
                    + "within minutes. Would book again.")
            String comment
    ) {
    }

    @Schema(description = "Review update: the new rating/comment")
    public record UpdateReviewRequest(
            @Schema(description = "Rating from 1 (worst) to 5 (best)", example = "4", minimum = "1",
                    maximum = "5")
            @NotNull @Min(1) @Max(5) Integer rating,
            @Schema(description = "The new public comment", example = "Updating after the host fixed "
                    + "the Wi-Fi — solid stay overall.")
            String comment
    ) {
    }

    @Schema(description = "Provider reply to a review")
    public record ReplyRequest(
            @Schema(description = "The provider's public reply", example = "Thank you for the kind "
                    + "words — looking forward to hosting you again.")
            @NotBlank String reply
    ) {
    }
}
