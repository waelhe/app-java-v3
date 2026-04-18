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
@RequestMapping(ApiConstants.REVIEWS)
public class ReviewsController {

    private final ReviewsService reviewsService;
    private final CurrentUserProvider currentUserProvider;

    public ReviewsController(ReviewsService reviewsService, CurrentUserProvider currentUserProvider) {
        this.reviewsService = reviewsService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Review> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewsService.getById(id));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<Review>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByProvider(providerId, pageable)));
    }

    @GetMapping("/reviewer/{reviewerId}")
    public ResponseEntity<PagedResponse<Review>> listByReviewer(
            @PathVariable UUID reviewerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(reviewsService.listByReviewer(reviewerId, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<Review> create(@Valid @RequestBody CreateReviewRequest request,
                                          Authentication authentication) {
        UUID reviewerId = currentUserProvider.getCurrentUserId(authentication);
        Review review = reviewsService.create(
                request.bookingId(), reviewerId,
                request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(review);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<Review> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateReviewRequest request,
                                          Authentication authentication) {
        return ResponseEntity.ok(reviewsService.update(id, request.rating(), request.comment(), authentication));
    }

    public record CreateReviewRequest(
            @NotNull UUID bookingId,
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {}

    public record UpdateReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {}
}
