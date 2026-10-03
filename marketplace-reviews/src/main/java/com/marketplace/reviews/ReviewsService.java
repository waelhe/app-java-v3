package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.api.ReviewMode;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingsPort;
import com.marketplace.shared.api.TooManyRequestsException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReviewsService {

    /** §4.5: the seeded setting's fallback value (the V72 row carries 5). */
    private static final int ORGANIC_DEFAULT_DAILY_CAP = 5;
    /** §4.5: the rolling window the daily cap counts over. */
    private static final Duration ORGANIC_CAP_WINDOW = Duration.ofHours(24);
    /** §4.5: the account-age floor for organic reviews ("كلفة صبر لا كلفة كود"). */
    private static final Duration ORGANIC_MIN_ACCOUNT_AGE = Duration.ofDays(7);
    /** §4.5: the first-N organic reviews of an account await moderation approval. */
    private static final long ORGANIC_QUEUE_SIZE = 3;
    /** §4.5 signal: the burst window and its threshold. */
    private static final Duration BURST_WINDOW = Duration.ofHours(1);
    private static final long BURST_THRESHOLD = 5;
    /** §4.5 signal: the young-account window (below the 7-day floor is blocked, not flagged). */
    private static final Duration NEW_ACCOUNT_SIGNAL_WINDOW = Duration.ofDays(30);
    /** §4.5 (D-N5): the moderation queue's complete FIFO drain order. */
    private static final Sort QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final ReviewRepository reviewRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final SystemSettingsPort systemSettingsPort;
    private final UserLookupPort userLookupPort;
    private final ProviderLookupPort providerLookupPort;
    private final ListingPriceProvider listingPriceProvider;
    private final ReviewVoteRepository reviewVoteRepository;
    private final ReviewFlagRepository reviewFlagRepository;
    private final Clock clock;

    public ReviewsService(ReviewRepository reviewRepository,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher,
                          BookingParticipantProvider bookingParticipantProvider,
                          SystemSettingsPort systemSettingsPort,
                          UserLookupPort userLookupPort,
                          ProviderLookupPort providerLookupPort,
                          ListingPriceProvider listingPriceProvider,
                          ReviewVoteRepository reviewVoteRepository,
                          ReviewFlagRepository reviewFlagRepository,
                          Clock clock) {
        this.reviewRepository = reviewRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.systemSettingsPort = systemSettingsPort;
        this.userLookupPort = userLookupPort;
        this.providerLookupPort = providerLookupPort;
        this.listingPriceProvider = listingPriceProvider;
        this.reviewVoteRepository = reviewVoteRepository;
        this.reviewFlagRepository = reviewFlagRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @Cacheable("reviews")
    public Review getById(UUID id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + id));
    }

    /**
     * W1 §4.5 — the single-read visibility gate, as a decision over an
     * already-loaded row: a PUBLISHED row is public; any other moderation
     * state is the author's own (or an admin's) view — everyone else answers
     * the honest 404 (a pending review never leaks its existence).
     * {@code tryGetCurrentUserId} keeps the real anonymous caller (no JWT)
     * out without an exception.
     *
     * <p><b>Why the gate is separate from the fetch:</b> {@link #getById} is
     * {@code @Cacheable("reviews")} and keyed by id alone — the entity is
     * caller-independent, so the cache entry is too. A gate that called
     * {@code this.getById(...)} would be a self-invocation, which bypasses
     * Spring's cache proxy: nothing would ever be PUT into the cache and the
     * documented cold-cache contract (a read whose DB row is gone still
     * answered from Redis) would silently stop holding. Keeping the fetch in
     * {@code getById} and the decision here lets the caller reach the cached
     * method across the bean boundary, while the rule itself still lives in
     * the data layer.
     */
    public void assertVisible(Review review, Authentication authentication) {
        if (review.getModerationStatus() == ReviewModerationStatus.PUBLISHED) {
            return;
        }
        if (authentication != null) {
            boolean admin = currentUserProvider.isAdmin(authentication);
            boolean author = currentUserProvider.tryGetCurrentUserId(authentication)
                    .map(caller -> caller.equals(review.getReviewerId()))
                    .orElse(false);
            if (admin || author) {
                return;
            }
        }
        throw new ResourceNotFoundException("Review not found: " + review.getId());
    }

    @Transactional(readOnly = true)
    public Page<Review> listByProvider(UUID providerId, Pageable pageable) {
        // I8: the surface keeps meaning "reviews ABOUT this provider" — the
        // forward direction (reverse reviews are the author's, not the
        // reviewed party's, surface). W1 §4.5: the public list is the
        // PUBLISHED gate — the plan's named visibility path 1.
        return reviewRepository.findByProviderIdAndDirectionAndModerationStatusOrderByCreatedAtDescIdDesc(
                providerId, ReviewDirection.CONSUMER_TO_PROVIDER,
                ReviewModerationStatus.PUBLISHED, pageable);
    }

    /**
     * W2 (greptile round 2, adopted from the root): the origin-scoped twin
     * of {@link #listByProvider} — the same forward direction and PUBLISHED
     * gate, narrowed to one origin population. The JSON-LD sample the public
     * page composes must ride the population its aggregate describes, never
     * a filter of the caller's requested page (an organic-only page after a
     * mode switch would otherwise leave the structured sample empty while
     * the aggregate reports verified reviews).
     */
    @Transactional(readOnly = true)
    public Page<Review> listByProviderAndOrigin(UUID providerId, String origin, Pageable pageable) {
        return reviewRepository.findByProviderIdAndDirectionAndModerationStatusAndOriginOrderByCreatedAtDescIdDesc(
                providerId, ReviewDirection.CONSUMER_TO_PROVIDER,
                ReviewModerationStatus.PUBLISHED, origin, pageable);
    }

    /**
     * W1 §4.5 — the reviewer surface with its owner branch: the author (or
     * an admin) sees every state of the authored reviews (the "my reviews"
     * view owes the account its pending badge); everyone else sees the
     * PUBLISHED gate (the public profile surface).
     */
    @Transactional(readOnly = true)
    public Page<Review> listByReviewer(UUID reviewerId, Pageable pageable, Authentication authentication) {
        boolean owner = false;
        if (authentication != null) {
            owner = currentUserProvider.isAdmin(authentication)
                    || currentUserProvider.tryGetCurrentUserId(authentication)
                            .map(caller -> caller.equals(reviewerId))
                            .orElse(false);
        }
        if (owner) {
            return reviewRepository.findByReviewerId(reviewerId, pageable);
        }
        return reviewRepository.findByReviewerIdAndModerationStatus(
                reviewerId, ReviewModerationStatus.PUBLISHED, pageable);
    }

    /**
     * I8: the reverse-review read surface — the reviews providers wrote
     * about one consumer (the trust view; the reviewed consumer's id in
     * the users.id space). W1: PUBLISHED-gated like every public read
     * (reverse reviews are booking-origin, therefore always published).
     */
    @Transactional(readOnly = true)
    public Page<Review> listByReviewee(UUID revieweeId, Pageable pageable) {
        return reviewRepository.findByRevieweeIdAndDirectionAndModerationStatus(
                revieweeId, ReviewDirection.PROVIDER_TO_CONSUMER,
                ReviewModerationStatus.PUBLISHED, pageable);
    }

    @Observed(name = "review.create")
    @PreAuthorize("hasRole('CONSUMER')")
    public Review create(UUID bookingId, UUID reviewerId,
                         Integer rating, String comment) {
        // W1 4.3 - the mode gate reads BEFORE any write. OPEN refuses the
        // verified path explicitly (the plan's ruled option: an explicit
        // 400, never a silent conversion of a booking review into an
        // organic one). VERIFIED_ONLY (the seed) and HYBRID admit it -
        // byte-for-byte the pre-W1 behaviour.
        if (mode() == ReviewMode.OPEN) {
            throw new BadRequestException(
                    "Booking reviews are not enabled in the current reviews mode (OPEN)"
                            + " - submit an organic review instead");
        }
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
     * gates mirror the forward path one-for-one: the caller must BE the
     * booking's provider, the booking must be COMPLETED, and at most one
     * reverse review exists per booking (per-direction uniqueness).
     *
     * <p><b>Ownership reads the ruling from the row itself (the A1
     * convention — the {@code verifyProviderOwnership} pattern in
     * BookingService):</b> {@code bookings.provider_id} physically carries
     * a <b>users.id</b> (V3: {@code references users(id)}; measured and
     * documented in {@code AuthHelper.ownsProvider}: «every cross-module
     * {@code provider_id} column carries a user id»). The gate therefore
     * compares the stored {@code bookingInfo.providerId()} directly
     * against the caller's user id — NO profile resolution: resolving
     * through {@code providerLookupPort.findByUserId(...).id()}
     * ({@code provider_profiles.id} space) is exactly the cross-space
     * mismatch that made this surface unusable live (the §9 surgical fix;
     * the same defect class A1 swept out of catalog/media/availability).
     *
     * <p>The reviewer is the provider's USER id; the reviewee is the
     * booking's consumer (stored in {@code reviewee_id}); the review's
     * providerId stores the AUTHORING provider's user id (A1). The same
     * events fire (ReviewCreatedEvent + the reviews cache invalidation) —
     * the L21 stats listener recomputes its average from FORWARD reviews
     * only, so the reverse review never pollutes it (the aggregate's
     * direction filter, same PR).
     */
    @Observed(name = "review.create.reverse")
    @PreAuthorize("hasRole('PROVIDER')")
    public Review createReverse(UUID bookingId, Integer rating, String comment,
                                Authentication authentication) {
        if (reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER)) {
            throw new ConflictException("Reverse review already exists for booking: " + bookingId);
        }

        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);

        // A1: the stored booking row is the ruling — its provider_id IS the
        // booking provider's user id (V3 FK), compared directly against the
        // caller (the verifyProviderOwnership pattern; no profile lookup).
        if (!bookingInfo.providerId().equals(currentUserId)) {
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
     * provider replies to its own review. <b>Ownership reads the ruling
     * from the row itself (the A1 convention — the
     * {@code verifyProviderOwnership} pattern in BookingService):</b>
     * {@code reviews.provider_id} physically carries a <b>users.id</b>
     * (V6: {@code references users(id)}) — the reviewed provider's user id
     * on forward rows, the authoring provider's user id on reverse rows.
     * The gate compares it directly against the caller's user id — NO
     * profile resolution: {@code providerLookupPort.findByUserId(...).id()}
     * resolves into the {@code provider_profiles.id} space, which never
     * equals the stored users.id — the cross-space mismatch that made the
     * reply surface unusable live (the §9 surgical fix). «المزوّد المستهدف
     * حصراً يملك الرد» — no admin bypass, the scope names the provider
     * exclusively. Uniqueness is by construction: {@link Review#reply(String)}
     * rejects a second reply.
     */
    @Observed(name = "review.reply")
    @PreAuthorize("hasRole('PROVIDER')")
    public Review reply(UUID id, String reply, Authentication authentication) {
        Review review = getById(id);
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        // A1: the stored review row is the ruling — its provider_id IS the
        // reviewed provider's user id (V6 FK), compared directly against the
        // caller (the verifyProviderOwnership pattern; no profile lookup).
        if (!review.getProviderId().equals(currentUserId)) {
            throw new AccessDeniedException("Only the reviewed provider can reply");
        }
        review.reply(reply);
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews"), id));
        return review;
    }

    /**
     * W1 (yelp-level plan §4.1 OPEN/HYBRID + §4.5) — the organic creation
     * gate. The plan's order, each gate the honest failure of its fact:
     * mode (VERIFIED_ONLY refuses the path outright); the provider resolves
     * (profile id in, USER id stored — A1) and the caller may not be that
     * provider; the account-age floor (younger than seven days answers
     * 400); the daily cap (seeded setting, rolling 24-hour window, 429);
     * 1x1 uniqueness (explicit 409 first, the V72 index the backstop);
     * the optional listing must resolve through the catalog port and
     * belong to the reviewed provider (the SPI gate — cross-table
     * ownership is not expressible as a CHECK and is not pretended to be).
     *
     * <p>After the gates: the first {@code ORGANIC_QUEUE_SIZE} organic
     * reviews of the account are queued PENDING_REVIEW (no stats event —
     * nothing public changed); a published creation fires the existing
     * ReviewCreatedEvent exactly like the booking path. The §4.5 fraud
     * signals are recorded for the moderation queue — never a block.
     */
    @Observed(name = "review.create.organic")
    @PreAuthorize("isAuthenticated()")
    public Review createOrganic(UUID providerId, UUID listingId,
                                Integer rating, String comment, Authentication authentication) {
        if (mode() == ReviewMode.VERIFIED_ONLY) {
            throw new BadRequestException(
                    "Organic reviews are not enabled in the current reviews mode (VERIFIED_ONLY)");
        }

        UUID reviewerId = currentUserProvider.getCurrentUserId(authentication);

        // W1 §4.5 (greptile W1 r9, adopted from the root): serialize this
        // reviewer's admission decisions — the daily-cap count, the 1x1
        // uniqueness check, and the first-N moderation count below are all
        // count-then-insert, so without the lock two concurrent submissions
        // would each read the same pre-insert state and both pass. The
        // advisory transaction lock (held until commit) is the media
        // repository's measured #241 shape, namespaced with a distinct seed.
        reviewRepository.lockReviewerDecisions(reviewerId.toString());

        ProviderSummary provider = providerLookupPort.findById(providerId)
                .filter(summary -> summary.userId() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + providerId));
        UUID providerUserId = provider.userId();

        if (providerUserId.equals(reviewerId)) {
            throw new BadRequestException("You cannot review your own business");
        }

        Instant accountCreatedAt = userLookupPort.findById(reviewerId)
                .map(UserSummary::createdAt)
                .orElse(null);
        if (accountCreatedAt == null
                || accountCreatedAt.isAfter(clock.instant().minus(ORGANIC_MIN_ACCOUNT_AGE))) {
            throw new BadRequestException(
                    "Your account must be at least " + ORGANIC_MIN_ACCOUNT_AGE.toDays()
                            + " days old to write an organic review");
        }

        int dailyCap = systemSettingsPort.getIntOrDefault(
                SystemSettingKeys.REVIEWS_ORGANIC_DAILY_CAP, ORGANIC_DEFAULT_DAILY_CAP);
        long recentOrganic = reviewRepository.countByReviewerIdAndOriginAndCreatedAtGreaterThanEqual(
                reviewerId, Review.ORIGIN_ORGANIC, clock.instant().minus(ORGANIC_CAP_WINDOW));
        if (recentOrganic >= dailyCap) {
            throw new TooManyRequestsException(
                    "Organic review daily limit reached (" + dailyCap + " per 24 hours)");
        }

        if (reviewRepository.existsByReviewerIdAndProviderIdAndOrigin(
                reviewerId, providerUserId, Review.ORIGIN_ORGANIC)) {
            throw new ConflictException("You already reviewed this provider");
        }

        if (listingId != null) {
            ListingPriceProvider.ListingInfo listing = listingPriceProvider.getListingInfo(listingId);
            if (!providerUserId.equals(listing.providerId())) {
                throw new BadRequestException("The listing does not belong to the reviewed provider");
            }
        }

        long authoredOrganic = reviewRepository.countByReviewerIdAndOrigin(reviewerId, Review.ORIGIN_ORGANIC);
        Review review = Review.createOrganic(reviewerId, providerUserId, listingId, rating, comment);
        if (authoredOrganic < ORGANIC_QUEUE_SIZE) {
            review.queueForReview();
        }
        Review saved = reviewRepository.save(review);

        recordAbuseSignals(saved);

        if (saved.isPublished()) {
            eventPublisher.publishEvent(new ReviewCreatedEvent(saved.getId()));
        }
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews")));
        return saved;
    }

    /**
     * W1 §4.5 — the moderation queue's approval. The one legal transition
     * is PENDING_REVIEW -> PUBLISHED (the entity gate answers 409
     * otherwise). The approval republishes the EXISTING creation event so
     * the stored provider averages recompute with the now-public row —
     * the plan's "بالموافقة يُطلق تحديث المجموع عبر الحدث القائم".
     */
    @Observed(name = "review.moderate.approve")
    @PreAuthorize("hasRole('ADMIN')")
    public Review approve(UUID id) {
        Review review = getById(id);
        review.approveByModerator();
        eventPublisher.publishEvent(new ReviewCreatedEvent(review.getId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews"), id));
        return review;
    }

    /**
     * W1 §4.5 — the moderation queue's reject. Only a PENDING_REVIEW item
     * can be rejected (the honest 409 otherwise); the row flips to
     * HIDDEN_BY_MODERATOR and the update event recomputes the stored
     * averages without it.
     */
    @Observed(name = "review.moderate.reject")
    @PreAuthorize("hasRole('ADMIN')")
    public Review reject(UUID id) {
        Review review = getById(id);
        if (review.getModerationStatus() != ReviewModerationStatus.PENDING_REVIEW) {
            throw new ConflictException(
                    "Only a PENDING_REVIEW review can be rejected - current status: "
                            + review.getModerationStatus());
        }
        review.hideByModerator();
        eventPublisher.publishEvent(new ReviewUpdatedEvent(review.getId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("reviews"), id));
        return review;
    }

    /**
     * W1 §4.5 — the report-resolve hide (the {@code ReviewLookupPort}
     * seam): a PUBLISHED review flips to HIDDEN_BY_MODERATOR inside the
     * caller's transaction, the update event recomputes the averages, and
     * the author id comes back for the caller's alert. An unknown,
     * soft-deleted, pending or already-hidden row answers empty — the
     * documented skip (the hide goal is already met; no second flip, no
     * duplicate alert). The admin authorization rides the caller's
     * authenticated chain and is re-checked here (defense in depth — the
     * port's caller runs inside the admin-gated resolve).
     */
    @Observed(name = "review.moderate.hide")
    @PreAuthorize("hasRole('ADMIN')")
    public Optional<UUID> hideByModerator(UUID id) {
        return reviewRepository.findById(id)
                .filter(review -> review.getModerationStatus() == ReviewModerationStatus.PUBLISHED)
                .map(review -> {
                    review.hideByModerator();
                    eventPublisher.publishEvent(new ReviewUpdatedEvent(review.getId()));
                    eventPublisher.publishEvent(
                            new CacheInvalidationRequested(Set.of("reviews"), review.getId()));
                    return review.getReviewerId();
                });
    }

    /**
     * W1 §4.5 — the moderation queue read: status-filtered, on the
     * complete FIFO drain order (createdAt ASC, id ASC — D-N5: the
     * operator drains oldest-first). Each item carries its internal fraud
     * flags (the §4.5 signals), batch-resolved for the whole page.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<ModerationQueueItem> moderationQueue(ReviewModerationStatus status, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), QUEUE_SORT);
        Page<Review> page = reviewRepository.findByModerationStatus(status, queuePageable);
        Set<UUID> ids = page.getContent().stream().map(Review::getId).collect(Collectors.toSet());
        Map<UUID, List<String>> flagsByReview = ids.isEmpty()
                ? Map.of()
                : reviewFlagRepository.findByReviewIdIn(ids).stream()
                        .collect(Collectors.groupingBy(ReviewFlag::getReviewId,
                                Collectors.mapping(flag -> flag.getFlagType().name(), Collectors.toList())));
        return page.map(review -> new ModerationQueueItem(
                review.getId(), review.getReviewerId(), review.getProviderId(),
                review.getRating(), review.getComment(), review.getOrigin(),
                review.getListingId(), review.getCreatedAt(),
                flagsByReview.getOrDefault(review.getId(), List.of())));
    }

    /** W1 §4.5: one moderation-queue row — the review plus its internal flag vocabulary. */
    public record ModerationQueueItem(
            UUID id,
            UUID reviewerId,
            UUID providerId,
            Integer rating,
            String comment,
            String origin,
            UUID listingId,
            Instant createdAt,
            List<String> flags
    ) {
    }

    /**
     * W1 §4.5 — a "helpful" vote. Only the PUBLISHED surface is votable
     * (the honest 404 otherwise — the visibility rule); the author cannot
     * vote his own review (400); one vote per (review, voter) — the
     * explicit 409 first, the V74 partial unique index the backstop.
     */
    @Observed(name = "review.vote")
    @PreAuthorize("isAuthenticated()")
    public void voteHelpful(UUID reviewId, Authentication authentication) {
        Review review = getById(reviewId);
        if (!review.isPublished()) {
            throw new ResourceNotFoundException("Review not found: " + reviewId);
        }
        UUID voterId = currentUserProvider.getCurrentUserId(authentication);
        if (review.getReviewerId().equals(voterId)) {
            throw new BadRequestException("You cannot mark your own review as helpful");
        }
        if (reviewVoteRepository.existsByReviewIdAndVoterId(reviewId, voterId)) {
            throw new ConflictException("You already marked this review as helpful");
        }
        reviewVoteRepository.save(ReviewVote.create(reviewId, voterId));
    }

    /** W1 §4.5 — the unvote: the soft delete frees the (review, voter) pair, so a re-vote is legal by construction. */
    @Observed(name = "review.vote.remove")
    @PreAuthorize("isAuthenticated()")
    public void unvoteHelpful(UUID reviewId, Authentication authentication) {
        UUID voterId = currentUserProvider.getCurrentUserId(authentication);
        ReviewVote vote = reviewVoteRepository.findByReviewIdAndVoterId(reviewId, voterId)
                .orElseThrow(() -> new ResourceNotFoundException("Helpful vote not found"));
        reviewVoteRepository.delete(vote);
    }

    /**
     * W1 §4.1 — the mode read: the caller's own default covers the "no
     * row" case only; an unknown stored value fails loud through
     * {@code ReviewMode.parse} (the W0 no-silent-fallback contract).
     */
    private ReviewMode mode() {
        return ReviewMode.parse(systemSettingsPort.getStringOrDefault(
                SystemSettingKeys.REVIEWS_MODE, ReviewMode.VERIFIED_ONLY.name()));
    }

    /**
     * W1 §4.5 — the fraud signals: recorded for the moderation queue,
     * never a block (the plan's first-release rule). Each signal is a
     * cheap indexed probe; the details string is the operator's evidence.
     */
    private void recordAbuseSignals(Review saved) {
        if (!Review.ORIGIN_ORGANIC.equals(saved.getOrigin())) {
            return;
        }
        Instant now = clock.instant();

        long burst = reviewRepository.countByProviderIdAndOriginAndCreatedAtGreaterThanEqual(
                saved.getProviderId(), Review.ORIGIN_ORGANIC, now.minus(BURST_WINDOW));
        if (burst >= BURST_THRESHOLD) {
            reviewFlagRepository.save(ReviewFlag.create(saved.getId(),
                    ReviewFlag.FlagType.BURST_ON_PROVIDER,
                    burst + " organic reviews on this provider within the last hour"));
        }

        userLookupPort.findById(saved.getReviewerId())
                .map(UserSummary::createdAt)
                .filter(createdAt -> createdAt.isAfter(now.minus(NEW_ACCOUNT_SIGNAL_WINDOW)))
                .ifPresent(createdAt -> reviewFlagRepository.save(ReviewFlag.create(saved.getId(),
                        ReviewFlag.FlagType.NEW_ACCOUNT_ACTIVITY,
                        "Reviewer account is younger than " + NEW_ACCOUNT_SIGNAL_WINDOW.toDays() + " days")));

        if (saved.getComment() != null && !saved.getComment().isBlank()
                && reviewRepository.existsOrganicWithNormalizedComment(
                        saved.getReviewerId(), saved.getComment(), saved.getId())) {
            reviewFlagRepository.save(ReviewFlag.create(saved.getId(),
                    ReviewFlag.FlagType.TEXT_SIMILARITY,
                    "Comment text matches another organic review by the same reviewer"));
        }
    }
}
