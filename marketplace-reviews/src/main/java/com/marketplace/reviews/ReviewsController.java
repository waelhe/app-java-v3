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
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.REVIEWS, version = "1.0")
public class ReviewsController {

    private final ReviewsService reviewsService;
    private final ReviewsViewService reviewsViewService;
    private final CurrentUserProvider currentUserProvider;

    public ReviewsController(ReviewsService reviewsService,
                             ReviewsViewService reviewsViewService,
                             CurrentUserProvider currentUserProvider) {
        this.reviewsService = reviewsService;
        this.reviewsViewService = reviewsViewService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * W1 §4.5: the single read rides the visibility gate — a PUBLISHED
     * review is public; a pending/hidden one is the author's own (or an
     * admin's), everyone else gets the honest 404.
     *
     * <p>B-09 (compliance plan B.3 — mvc-caching.html): the read is now
     * CONDITIONAL — the ETag is the response's own content fingerprint
     * ({@link ReviewEtags#forReview}), and an If-None-Match match answers
     * 304 with no body (the offline client's one-round-trip revalidation).
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get one review", description = "A single published review with the "
            + "provider reply when one exists. A non-published review is visible to its author "
            + "and to admins only (404 for everyone else). Conditional: an If-None-Match match "
            + "answers 304 Not Modified.")
    public ResponseEntity<ReviewResponse> getById(@PathVariable UUID id, Authentication authentication,
                                                   WebRequest request) {
        ReviewResponse response = reviewsViewService.getVisible(id, authentication);
        return conditional(ReviewEtags.forReview(response), request, response);
    }

    /**
     * B-09 (B.3): the shared conditional wiring — checkNotModified on the
     * content fingerprint: a match answers 304 with the ETag and no body,
     * otherwise 200 with the ETag set (the client's next revalidation key).
     */
    private <T> ResponseEntity<T> conditional(String eTagValue, WebRequest request, T body) {
        if (request.checkNotModified(eTagValue)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag("\"" + eTagValue + "\"")
                    .build();
        }
        return ResponseEntity.ok()
                .eTag("\"" + eTagValue + "\"")
                .body(body);
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List a provider's reviews", description = "Paginated public reviews of a "
            + "provider (consumer-to-provider direction), newest first. PUBLISHED reviews only — "
            + "a pending or moderator-hidden review is absent. Conditional: an If-None-Match "
            + "match answers 304 Not Modified.")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable, WebRequest request) {
        PagedResponse<ReviewResponse> page = PagedResponse.of(reviewsViewService.listByProvider(providerId, pageable));
        return conditional(ReviewEtags.forPage("provider", providerId, page), request, page);
    }

    @GetMapping("/reviewer/{reviewerId}")
    @Operation(summary = "List a reviewer's reviews", description = "Paginated reviews written by "
            + "one user. The reviews' author (or an admin) sees every moderation state; everyone "
            + "else sees the published surface only. Conditional: an If-None-Match match answers "
            + "304 Not Modified.")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByReviewer(
            @PathVariable UUID reviewerId, Pageable pageable, Authentication authentication, WebRequest request) {
        PagedResponse<ReviewResponse> page = PagedResponse.of(
                reviewsViewService.listByReviewer(reviewerId, pageable, authentication));
        return conditional(ReviewEtags.forPage("reviewer", reviewerId, page), request, page);
    }

    /**
     * I8 (internal free plan §6, roadmap §7): the reverse-direction read
     * surface — the reviews providers wrote about one consumer (the trust
     * view).
     */
    @GetMapping("/consumer/{consumerId}")
    @Operation(summary = "List the reviews written about a consumer", description = "Paginated "
            + "provider-to-consumer reviews (I8 reverse direction) — the consumer's "
            + "trust surface: what providers said about them after completed bookings. "
            + "Conditional: an If-None-Match match answers 304 Not Modified.")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByReviewee(
            @PathVariable UUID consumerId, Pageable pageable, WebRequest request) {
        PagedResponse<ReviewResponse> page = PagedResponse.of(reviewsViewService.listByReviewee(consumerId, pageable));
        return conditional(ReviewEtags.forPage("consumer", consumerId, page), request, page);
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
                    + "provider rating average updates asynchronously. In the OPEN reviews mode the "
                    + "booking path is refused (400) — submit an organic review instead.")
    public ResponseEntity<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest request,
                                                 Authentication authentication) {
        UUID reviewerId = currentUserProvider.getCurrentUserId(authentication);
        Review review = reviewsService.create(
                request.bookingId(), reviewerId,
                request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewsViewService.toResponse(review));
    }

    /**
     * W1 (yelp-level plan §4.1 OPEN/HYBRID + §4.5): the organic write — a
     * general review with no booking, behind the mode gate and the full
     * anti-abuse set at the service (account age, daily cap, 1x1
     * uniqueness, optional-listing ownership; the first three organic
     * reviews of an account await moderation). Same rate-limiter budget as
     * every review write.
     */
    @PostMapping("/organic")
    @RateLimiter(name = "reviewCreate")
    @Operation(summary = "Create an organic review",
            description = "A general review without a booking — enabled in the OPEN/HYBRID reviews "
                    + "mode only. The reviewed provider is resolved by profile id; the reviewer "
                    + "must be a registered account (not the provider itself), at least 7 days "
                    + "old, within the daily organic-review cap. An optional listingId must belong "
                    + "to the reviewed provider. One organic review per (reviewer, provider) — "
                    + "forever.")
    public ResponseEntity<ReviewResponse> createOrganic(@Valid @RequestBody CreateOrganicReviewRequest request,
                                                        Authentication authentication) {
        Review review = reviewsService.createOrganic(
                request.providerId(), request.listingId(),
                request.rating(), request.comment(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewsViewService.toResponse(review));
    }

    /**
     * I8 (internal free plan §6, roadmap §7): the reverse write — the
     * booking's provider rates its consumer. Same request shape, same
     * reviewCreate rate-limiter budget (a review write is a review write),
     * gates at the service: provider profile ownership of the booking +
     * COMPLETED + one reverse review per booking.
     */
    @PostMapping("/reverse")
    @RateLimiter(name = "reviewCreate")
    @Operation(summary = "Create a reverse review (provider)",
            description = "The booking's provider rates its consumer after a COMPLETED booking — "
                    + "one reverse review per booking. The provider's own rating average is "
                    + "unaffected (it aggregates consumer reviews only).")
    public ResponseEntity<ReviewResponse> createReverse(@Valid @RequestBody CreateReviewRequest request,
                                                         Authentication authentication) {
        Review review = reviewsService.createReverse(
                request.bookingId(), request.rating(), request.comment(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewsViewService.toResponse(review));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update my review", description = "The original reviewer may edit the "
            + "rating/comment; the provider average recomputes.")
    public ResponseEntity<ReviewResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateReviewRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.ok(reviewsViewService.toResponse(
                reviewsService.update(id, request.rating(), request.comment(), authentication)));
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
        return ResponseEntity.ok(reviewsViewService.toResponse(
                reviewsService.reply(id, request.reply(), authentication)));
    }

    /**
     * W1 (§4.5 — أصوات «مفيد»): mark a PUBLISHED review as helpful — one
     * vote per (review, voter); the author cannot vote his own review.
     */
    @PostMapping("/{id}/votes")
    @Operation(summary = "Mark a review as helpful",
            description = "One helpful vote per (review, voter) on the PUBLISHED surface. The "
                    + "review's own author cannot vote; a duplicate answers 409.")
    public ResponseEntity<Void> voteHelpful(@PathVariable UUID id, Authentication authentication) {
        reviewsService.voteHelpful(id, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * W1 (§4.5): remove my helpful vote — the soft delete frees the pair,
     * so a re-vote is legal by construction.
     */
    @DeleteMapping("/{id}/votes")
    @Operation(summary = "Remove my helpful vote", description = "Removes the caller's helpful vote "
            + "from the review. 404 when no live vote exists.")
    public ResponseEntity<Void> unvoteHelpful(@PathVariable UUID id, Authentication authentication) {
        reviewsService.unvoteHelpful(id, authentication);
        return ResponseEntity.noContent().build();
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

    /**
     * W1 (§4.1/§4.2): the organic write's shape — the provider PROFILE id
     * (the public provider page's own key; the server resolves the user-id
     * space), the optional listing target, the shared rating floor.
     */
    @Schema(description = "Organic review request: the reviewed provider, an optional listing "
            + "target, rating and comment")
    public record CreateOrganicReviewRequest(
            @Schema(description = "The reviewed provider's profile id", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @NotNull UUID providerId,
            @Schema(description = "Optional listing the review is about — must belong to the "
                    + "reviewed provider", example = "0d3b3f7e-1f2a-4c3b-9c2d-5e6f7a8b9c0d")
            UUID listingId,
            @Schema(description = "Rating from 1 (worst) to 5 (best)", example = "5", minimum = "1",
                    maximum = "5")
            @NotNull @Min(1) @Max(5) Integer rating,
            @Schema(description = "Optional public comment", example = "Great local bakery — the "
                    + "sourdough sells out by noon.")
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
