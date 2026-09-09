package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ReviewsService {

    private final ReviewRepository reviewRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final ProviderLookupPort providerLookupPort;

    public ReviewsService(ReviewRepository reviewRepository,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher,
                          BookingParticipantProvider bookingParticipantProvider,
                          ProviderLookupPort providerLookupPort) {
        this.reviewRepository = reviewRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.providerLookupPort = providerLookupPort;
    }

    @Transactional(readOnly = true)
    @Cacheable("reviews")
    public Review getById(UUID id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Review> listByProvider(UUID providerId, Pageable pageable) {
        // I8: the surface keeps meaning "reviews ABOUT this provider" — the
        // forward direction (reverse reviews are the author's, not the
        // reviewed party's, surface).
        return reviewRepository.findByProviderIdAndDirection(
                providerId, ReviewDirection.CONSUMER_TO_PROVIDER, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Review> listByReviewer(UUID reviewerId, Pageable pageable) {
        return reviewRepository.findByReviewerId(reviewerId, pageable);
    }

    /**
     * I8: the reverse-review read surface — the reviews providers wrote
     * about one consumer (the trust view; the reviewed consumer's id in
     * the users.id space).
     */
    @Transactional(readOnly = true)
    public Page<Review> listByReviewee(UUID revieweeId, Pageable pageable) {
        return reviewRepository.findByRevieweeIdAndDirection(
                revieweeId, ReviewDirection.PROVIDER_TO_CONSUMER, pageable);
    }

    @Observed(name = "review.create")
    @PreAuthorize("hasRole('CONSUMER')")
    public Review create(UUID bookingId, UUID reviewerId,
                         Integer rating, String comment) {
        // I8: per-direction uniqueness — a booking may carry BOTH the
        // consumer's review and the provider's reverse review (one each).
        if (reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)) {
            throw new ConflictException("Review already exists for booking: " + bookingId);
        }

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);

        if (!bookingInfo.consumerId().equals(reviewerId)) {
            throw new AccessDeniedException("Only the booking consumer can submit a review");
        }

        if (!"COMPLETED".equals(bookingInfo.status())) {
            throw new BadRequestException("Cannot review a booking that is not COMPLETED");
        }

        Review saved = reviewRepository.save(
                Review.create(bookingId, reviewerId, bookingInfo.providerId(), rating, comment));
        eventPublisher.publishEvent(new ReviewCreatedEvent(saved.getId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews")));
        return saved;
    }

    /**
     * I8 (internal free plan §6, roadmap §7 — the deferred product decision
     * "تقييم المزوّد للمستهلك (اتجاه معاكس)" executed on the user's order):
     * the reverse review — the booking's provider rates its consumer. The
     * gates mirror the forward path one-for-one: the caller must own the
     * provider profile that IS the booking's provider (the L20/L21 seam —
     * {@code ProviderLookupPort.findByUserId}, the same ownership pattern
     * {@link #reply} applies), the booking must be COMPLETED, and at most
     * one reverse review exists per booking (per-direction uniqueness).
     *
     * <p>The reviewer is the provider's USER id; the reviewee is the
     * booking's consumer (stored in {@code reviewee_id}); the review's
     * providerId is the AUTHORING provider's profile id. The same events
     * fire (ReviewCreatedEvent + the reviews cache invalidation) — the L21
     * stats listener resolves the authoring provider and recomputes its
     * average from FORWARD reviews only, so the reverse review never
     * pollutes it (the aggregate's direction filter, same PR).
     */
    @Observed(name = "review.create.reverse")
    @PreAuthorize("hasRole('PROVIDER')")
    public Review createReverse(UUID bookingId, Integer rating, String comment,
                                Authentication authentication) {
        if (reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER)) {
            throw new ConflictException("Reverse review already exists for booking: " + bookingId);
        }

        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        UUID callerProviderId = providerLookupPort.findByUserId(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("You do not own a provider profile"))
                .id();

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);

        if (!bookingInfo.providerId().equals(callerProviderId)) {
            throw new AccessDeniedException("Only the booking provider can submit a reverse review");
        }

        if (!"COMPLETED".equals(bookingInfo.status())) {
            throw new BadRequestException("Cannot review a booking that is not COMPLETED");
        }

        Review saved = reviewRepository.save(Review.createReverse(
                bookingId, currentUserId, bookingInfo.providerId(), bookingInfo.consumerId(),
                rating, comment));
        eventPublisher.publishEvent(new ReviewCreatedEvent(saved.getId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews")));
        return saved;
    }

    @Observed(name = "review.update")
    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public Review update(UUID id, Integer rating, String comment, Authentication authentication) {
        // I8: the author of a reverse review is a PROVIDER — the role gate is
        // the coarse pre-filter, the real guard is verifyOwnership (the
        // author — of either direction — or an admin, nobody else).
        Review review = getById(id);
        verifyOwnership(review, authentication);
        review.update(rating, comment);
        eventPublisher.publishEvent(new ReviewUpdatedEvent(id));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews"), id));
        return review;
    }

    private void verifyOwnership(Review review, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!review.getReviewerId().equals(currentUserId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("You did not write this review");
        }
    }

    /**
     * L21 (roadmap §5) — the provider side of the two-way review: the target
     * provider replies to its own review. Ownership follows the L20 seam:
     * the caller's user id resolves to their provider profile
     * ({@code ProviderLookupPort.findByUserId}) and that profile must BE the
     * review's provider — «المزوّد المستهدف حصراً يملك الرد» (no admin bypass,
     * the scope names the provider exclusively). Uniqueness is by
     * construction: {@link Review#reply(String)} rejects a second reply.
     */
    @Observed(name = "review.reply")
    @PreAuthorize("hasRole('PROVIDER')")
    public Review reply(UUID id, String reply, Authentication authentication) {
        Review review = getById(id);
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        UUID callerProviderId = providerLookupPort.findByUserId(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("You do not own a provider profile"))
                .id();
        if (!review.getProviderId().equals(callerProviderId)) {
            throw new AccessDeniedException("Only the reviewed provider can reply");
        }
        review.reply(reply);
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews"), id));
        return review;
    }
}
