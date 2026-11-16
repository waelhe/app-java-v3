package com.marketplace.reviews;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * W1 (yelp-level plan §4.5 — the moderation surface): the review
 * moderation queue's admin surface, "بنمط L30" like the community's
 * ModerationAdminController — the chain's {@code /api/v1/admin/** ->
 * hasRole(ADMIN)} rule + this class-level {@code @PreAuthorize} + the
 * service-level gate as the third layer.
 *
 * <p><b>Why the surface lives in the reviews module (the measured module
 * law):</b> the community module's controller cannot flip a review (its
 * allowed dependencies are shared-only), so the Review aggregate's
 * transitions stay with their owner — the per-module admin-controller
 * pattern the house already runs (GeoAdminController,
 * CategoryAdminController, ModerationAdminController).
 *
 * <p><b>The two endpoints:</b> the queue read {@code GET
 * /api/v1/admin/reviews/moderation?status=} (default PENDING_REVIEW,
 * complete FIFO drain order, internal fraud flags included) and the
 * decision command {@code POST /api/v1/admin/reviews/{id}/moderate} with
 * {@code {action: APPROVE|REJECT}}.
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class ReviewModerationAdminController {

    private final ReviewsService reviewsService;
    private final ReviewsViewService reviewsViewService;

    public ReviewModerationAdminController(ReviewsService reviewsService,
                                           ReviewsViewService reviewsViewService) {
        this.reviewsService = reviewsService;
        this.reviewsViewService = reviewsViewService;
    }

    @GetMapping("/reviews/moderation")
    @Operation(summary = "Read the review moderation queue (admin)",
            description = "The review moderation queue ordered by the aggregate fraud-signal count "
                    + "first (W5: the signal ranks what the moderator sees first — the human still "
                    + "decides everything), then the oldest-first FIFO drain; each item carrying its "
                    + "internal fraud flags. The optional status axis filters PENDING_REVIEW (the "
                    + "default), PUBLISHED or HIDDEN_BY_MODERATOR.")
    public ResponseEntity<PagedResponse<ReviewsService.ModerationQueueItem>> queue(
            @Parameter(description = "Optional status filter — PENDING_REVIEW (default), PUBLISHED "
                    + "or HIDDEN_BY_MODERATOR")
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                reviewsService.moderationQueue(parseStatus(status), pageable)));
    }

    /** The status gate (the parseCategory convention): blank = the queue's default, invalid = 400. */
    private static ReviewModerationStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReviewModerationStatus.PENDING_REVIEW;
        }
        try {
            return ReviewModerationStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException("Invalid status '" + raw
                    + "' — valid values: PENDING_REVIEW, PUBLISHED, HIDDEN_BY_MODERATOR");
        }
    }

    @PostMapping("/reviews/{reviewId}/moderate")
    @Operation(summary = "Moderate a pending review (admin)",
            description = "The decision command on the review moderation queue. APPROVE: the "
                    + "review flips PENDING_REVIEW -> PUBLISHED and the stored provider averages "
                    + "recompute with it (the existing creation event, AFTER_COMMIT). REJECT: the "
                    + "review flips to HIDDEN_BY_MODERATOR and the averages recompute without it. "
                    + "Any other stored state answers 409; an unknown review answers 404.")
    public ResponseEntity<ReviewResponse> moderate(@PathVariable UUID reviewId,
                                                   @Valid @RequestBody ModerateReviewRequest request) {
        Review review = switch (parseAction(request.action())) {
            case APPROVE -> reviewsService.approve(reviewId);
            case REJECT -> reviewsService.reject(reviewId);
        };
        return ResponseEntity.ok(reviewsViewService.toResponse(review));
    }

    /** The action gate — the same String-in/enum-out convention. */
    private static ModerationAction parseAction(String raw) {
        try {
            return ModerationAction.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid action '" + raw + "' — valid values: APPROVE, REJECT");
        }
    }

    private enum ModerationAction {
        APPROVE,
        REJECT
    }

    /** The decision body: the one transition out of PENDING_REVIEW, as a word. */
    public record ModerateReviewRequest(
            @NotBlank
            @Schema(description = "The moderation outcome — publish or hide.",
                    allowableValues = {"APPROVE", "REJECT"}, example = "APPROVE")
            String action
    ) {
    }
}
