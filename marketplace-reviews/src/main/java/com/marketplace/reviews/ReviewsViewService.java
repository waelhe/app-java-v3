package com.marketplace.reviews;

import com.marketplace.shared.api.UserLookupPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * W1 (yelp-level plan §4.4/§4.5) — the reviews read-side assembly: every
 * {@link ReviewResponse} the API serves is composed here from three
 * batch-resolved blocks (a whole page costs four queries flat — the
 * ProviderPublicPageService house pattern of a dedicated read service,
 * plus the batch-resolution rule the identity port documents):
 *
 * <ul>
 *   <li>reviewer names — one {@code findAllByIds} for the page's distinct
 *       authors; the name honours {@code pseudonymized_at} through
 *       {@code UserSummary.publicDisplayName()};</li>
 *   <li>reviewer published-review counts — one grouped query;</li>
 *   <li>helpful-vote counts — one grouped query.</li>
 * </ul>
 *
 * <p>The visibility gates stay in {@link ReviewsService} (the data layer);
 * this service only shapes responses — default method mapping plus the
 * three blocks.
 */
@Service
@Transactional(readOnly = true)
public class ReviewsViewService {

    /** The honest fallback when an author row is absent entirely (the UserSummary fallback's twin). */
    private static final String DEFAULT_REVIEWER_NAME = "User";

    private final ReviewsService reviewsService;
    private final ReviewRepository reviewRepository;
    private final ReviewVoteRepository reviewVoteRepository;
    private final UserLookupPort userLookupPort;
    private final ReviewMapper reviewMapper;

    public ReviewsViewService(ReviewsService reviewsService,
                              ReviewRepository reviewRepository,
                              ReviewVoteRepository reviewVoteRepository,
                              UserLookupPort userLookupPort,
                              ReviewMapper reviewMapper) {
        this.reviewsService = reviewsService;
        this.reviewRepository = reviewRepository;
        this.reviewVoteRepository = reviewVoteRepository;
        this.userLookupPort = userLookupPort;
        this.reviewMapper = reviewMapper;
    }

    /** The composed response for one entity (the write paths' return shape). */
    public ReviewResponse toResponse(Review review) {
        return assemble(List.of(review)).getFirst();
    }

    /**
     * W1 §4.5: the single read through the visibility gate (published OR
     * author/admin, else 404).
     *
     * <p>The fetch is {@link ReviewsService#getById} — the {@code @Cacheable}
     * one — and the gate is the data layer's {@code assertVisible}. Calling
     * the cached method from here, across the bean boundary, is what keeps
     * the {@code reviews} cache populated on the single-read path (an
     * in-class call would be a self-invocation that Spring's cache proxy
     * never sees).
     */
    public ReviewResponse getVisible(UUID id, Authentication authentication) {
        Review review = reviewsService.getById(id);
        reviewsService.assertVisible(review, authentication);
        return toResponse(review);
    }

    public Page<ReviewResponse> listByProvider(UUID providerId, Pageable pageable) {
        return assemblePage(reviewsService.listByProvider(providerId, pageable));
    }

    public Page<ReviewResponse> listByReviewer(UUID reviewerId, Pageable pageable, Authentication authentication) {
        return assemblePage(reviewsService.listByReviewer(reviewerId, pageable, authentication));
    }

    public Page<ReviewResponse> listByReviewee(UUID revieweeId, Pageable pageable) {
        return assemblePage(reviewsService.listByReviewee(revieweeId, pageable));
    }

    private Page<ReviewResponse> assemblePage(Page<Review> page) {
        return new PageImpl<>(assemble(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    private List<ReviewResponse> assemble(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return List.of();
        }
        Set<UUID> reviewIds = reviews.stream().map(Review::getId).collect(Collectors.toSet());
        Set<UUID> reviewerIds = reviews.stream().map(Review::getReviewerId).collect(Collectors.toSet());

        Map<UUID, String> reviewerNames = userLookupPort.findAllByIds(reviewerIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().publicDisplayName()));
        Map<UUID, Long> reviewCounts = reviewRepository.countPublishedByReviewerIds(reviewerIds).stream()
                .collect(Collectors.toMap(ReviewerReviewCount::reviewerId, ReviewerReviewCount::count));
        Map<UUID, Long> helpfulCounts = reviewVoteRepository.countByReviewIds(reviewIds).stream()
                .collect(Collectors.toMap(ReviewVoteCount::reviewId, ReviewVoteCount::count));

        return reviews.stream()
                .map(review -> reviewMapper.toResponse(
                        review,
                        reviewerNames.getOrDefault(review.getReviewerId(), DEFAULT_REVIEWER_NAME),
                        reviewCounts.getOrDefault(review.getReviewerId(), 0L),
                        helpfulCounts.getOrDefault(review.getId(), 0L)))
                .toList();
    }
}
