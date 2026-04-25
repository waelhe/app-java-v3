package com.marketplace.reviews;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.REVIEWS, version = "1.0")
public class ReviewsController {

    private final ReviewsService reviewsService;
    private final CurrentUserProvider currentUserProvider;

    public ReviewsController(ReviewsService reviewsService, CurrentUserProvider currentUserProvider) {
        this.reviewsService = reviewsService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ReviewResponse.from(reviewsService.getById(id)));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByProvider(providerId, pageable).map(ReviewResponse::from)));
    }

    @GetMapping("/reviewer/{reviewerId}")
    public ResponseEntity<PagedResponse<ReviewResponse>> listByReviewer(
            @PathVariable UUID reviewerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByReviewer(reviewerId, pageable).map(ReviewResponse::from)));
    }

    @PostMapping
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest request,
                                                 Authentication authentication) {
        UUID reviewerId = currentUserProvider.getCurrentUserId(authentication);
        Review review = reviewsService.create(
                request.bookingId(), reviewerId,
                request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.from(review));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<ReviewResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateReviewRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.ok(ReviewResponse.from(reviewsService.update(id, request.rating(), request.comment(), authentication)));
    }

    public record CreateReviewRequest(
            @NotNull UUID bookingId,
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {
    }

    public record UpdateReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {
    }
}
