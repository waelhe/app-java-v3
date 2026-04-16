package com.marketplace.reviews;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.REVIEWS)
public class ReviewsController {

    private final ReviewsService reviewsService;

    public ReviewsController(ReviewsService reviewsService) {
        this.reviewsService = reviewsService;
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
    public ResponseEntity<Review> create(@RequestBody CreateReviewRequest request) {
        Review review = reviewsService.create(
                request.bookingId(), request.reviewerId(), request.providerId(),
                request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(review);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<Review> update(@PathVariable UUID id,
                                          @RequestBody UpdateReviewRequest request) {
        return ResponseEntity.ok(reviewsService.update(id, request.rating(), request.comment()));
    }

    public record CreateReviewRequest(
            @NotNull UUID bookingId,
            @NotNull UUID reviewerId,
            @NotNull UUID providerId,
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {}

    public record UpdateReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            String comment
    ) {}
}