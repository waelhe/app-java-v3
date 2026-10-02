package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ReviewMode;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingsPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewsServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final SystemSettingsPort systemSettingsPort = mock(SystemSettingsPort.class);
    private final UserLookupPort userLookupPort = mock(UserLookupPort.class);
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final ListingPriceProvider listingPriceProvider = mock(ListingPriceProvider.class);
    private final ReviewVoteRepository reviewVoteRepository = mock(ReviewVoteRepository.class);
    private final ReviewFlagRepository reviewFlagRepository = mock(ReviewFlagRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    private final Authentication authentication = mock(Authentication.class);
    private ReviewsService service;

    @BeforeEach
    void setUp() {
        service = new ReviewsService(reviewRepository, currentUserProvider, eventPublisher,
                bookingParticipantProvider, systemSettingsPort, userLookupPort,
                providerLookupPort, listingPriceProvider, reviewVoteRepository,
                reviewFlagRepository, clock);
        // The seed mode: every booking-path test below runs the pre-W1
        // behaviour byte for byte (the plan's "no modified line" criterion).
        when(systemSettingsPort.getStringOrDefault(
                org.mockito.ArgumentMatchers.eq(SystemSettingKeys.REVIEWS_MODE),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ReviewMode.VERIFIED_ONLY.name());
    }

    @Test
    void create_validReview_forCompletedBooking() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), providerId)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "COMPLETED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.create(bookingId, consumerId, 4, "Great service");

        assertEquals(4, review.getRating());
        assertEquals(providerId, review.getProviderId());
    }

    @Test
    void create_rejectsInvalidRating() {
        assertThrows(IllegalArgumentException.class,
                () -> Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class), Instancio.create(UUID.class), 0, "bad"));
    }

    @Test
    void create_rejectsDuplicateReview() {
        UUID bookingId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).thenReturn(true);
        assertThrows(ConflictException.class,
                () -> service.create(bookingId, Instancio.create(UUID.class), 3, "dup"));
    }

    @Test
    void create_rejectsNonConsumer() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID actualConsumerId = Instancio.create(UUID.class);
        UUID differentUserId = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), actualConsumerId)
                .set(field(BookingInfo::status), "COMPLETED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.create(bookingId, differentUserId, 3, "hacked"));
    }

    @Test
    void create_rejectsNonCompletedBooking() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> service.create(bookingId, consumerId, 3, "too early"));
    }

    @Test
    void update_changesRatingAndComment() {
        UUID id = Instancio.create(UUID.class);
        UUID reviewerId = Instancio.create(UUID.class);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getReviewerId), reviewerId)
                .set(field(Review::getRating), 3)
                .set(field(Review::getComment), "ok")
                .create();
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(reviewerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Review updated = service.update(id, 5, "excellent", authentication);
        assertEquals(5, updated.getRating());
        verify(eventPublisher).publishEvent(any(ReviewUpdatedEvent.class));
    }

    @Test
    void update_throwsWhenNotOwner() {
        UUID id = Instancio.create(UUID.class);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getRating), 3)
                .set(field(Review::getComment), "ok")
                .create();
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.update(id, 5, "excellent", authentication));
    }

    @Test
    void getById_returnsReview() {
        UUID id = Instancio.create(UUID.class);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getRating), 4)
                .create();
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));

        Review result = service.getById(id);

        assertNotNull(result);
        assertEquals(4, result.getRating());
    }

    @Test
    void getById_throwsWhenNotFound() {
        UUID id = Instancio.create(UUID.class);
        when(reviewRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(com.marketplace.shared.api.ResourceNotFoundException.class,
                () -> service.getById(id));
    }

    @Test
    void listByProvider_returnsPage() {
        UUID providerId = Instancio.create(UUID.class);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getProviderId), providerId)
                .set(field(Review::getRating), 4)
                .create();
        when(reviewRepository.findByProviderIdAndDirectionAndModerationStatusOrderByCreatedAtDescIdDesc(
                        providerId, ReviewDirection.CONSUMER_TO_PROVIDER,
                        ReviewModerationStatus.PUBLISHED, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));

        var result = service.listByProvider(providerId, pageable);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByReviewer_returnsPage() {
        UUID reviewerId = Instancio.create(UUID.class);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getReviewerId), reviewerId)
                .set(field(Review::getRating), 4)
                .create();
        // No authentication -> the PUBLIC branch: the PUBLISHED gate (§4.5).
        when(reviewRepository.findByReviewerIdAndModerationStatus(
                        reviewerId, ReviewModerationStatus.PUBLISHED, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));

        var result = service.listByReviewer(reviewerId, pageable, null);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByReviewer_ownerSeesEveryModerationState() {
        UUID reviewerId = Instancio.create(UUID.class);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Review pending = Instancio.of(Review.class)
                .set(field(Review::getReviewerId), reviewerId)
                .set(field(Review::getRating), 4)
                .set(field(Review::getModerationStatus), ReviewModerationStatus.PENDING_REVIEW)
                .create();
        when(reviewRepository.findByReviewerId(reviewerId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(pending)));
        // The identity seam answers the owner branch through the default
        // tryGetCurrentUserId contract (a JwtAuthenticationToken whose
        // subject resolves — see the app-level gate tests for the real
        // IdentityUserProvider path).
        org.springframework.security.oauth2.jwt.Jwt jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
                        .header("alg", "none").subject("owner-subject")
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(60))
                        .build();
        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken auth =
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        jwt, java.util.List.of(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONSUMER")));
        when(currentUserProvider.isAdmin(auth)).thenReturn(false);
        when(currentUserProvider.tryGetCurrentUserId(auth)).thenReturn(Optional.of(reviewerId));

        var result = service.listByReviewer(reviewerId, pageable, auth);

        assertEquals(1, result.getTotalElements());
        assertEquals(ReviewModerationStatus.PENDING_REVIEW,
                result.getContent().get(0).getModerationStatus());
    }

    // -- L21: reply ------------------------------------------------------
    // A1 (the §9 surgical gate fix): the review's provider_id carries the
    // provider's USER id (V6: references users(id)) — the gate compares it
    // directly against the caller's user id, the verifyProviderOwnership
    // pattern. NO profile resolution: the old tests coincided the two id
    // spaces through the lookup mock (the false confidence §9 measured —
    // the production write path stores users.id, so the resolved
    // provider_profiles.id never matched and the legitimate owner was
    // always denied). These seeds use the production space.

    private Review reviewFor(UUID providerUserId) {
        return Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class),
                providerUserId, 4, "Good");
    }

    @Test
    void reply_targetProviderSetsReplyAndInvalidatesCache() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        Review review = reviewFor(providerUserId);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerUserId);

        Review result = service.reply(reviewId, "Thanks for the feedback", authentication);

        assertEquals("Thanks for the feedback", result.getReply());
        assertNotNull(result.getRepliedAt());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.CacheInvalidationRequested));
    }

    @Test
    void reply_throwsForUserWhoIsNotTheStoredProvider() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        UUID otherUserId = Instancio.create(UUID.class);
        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(reviewFor(providerUserId)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherUserId);

        assertThrows(AccessDeniedException.class,
                () -> service.reply(reviewId, "not mine", authentication));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reply_throwsForDifferentProvider() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID storedProviderUserId = Instancio.create(UUID.class);
        UUID otherProviderUserId = Instancio.create(UUID.class);
        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(reviewFor(storedProviderUserId)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherProviderUserId);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.reply(reviewId, "not mine", authentication));

        assertTrue(ex.getMessage().contains("reviewed provider"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reply_throwsWhenAlreadyReplied() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        Review review = reviewFor(providerUserId);
        review.reply("first");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerUserId);

        assertThrows(ConflictException.class, () -> service.reply(reviewId, "second", authentication));
        assertEquals("first", review.getReply());
    }

    @Test
    void reply_throwsWhenReviewMissing() {
        UUID reviewId = Instancio.create(UUID.class);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(com.marketplace.shared.api.ResourceNotFoundException.class,
                () -> service.reply(reviewId, "x", authentication));
    }

    // -- I8: the reverse review (provider -> consumer) ------------------------
    // A1: the booking's provider_id carries the provider's USER id (V3:
    // references users(id)) — the createReverse gate compares it directly
    // against the caller's user id (the verifyProviderOwnership pattern,
    // the §9 surgical fix). The completedBooking seeds below use the
    // production space: providerId = the provider's USER id.

    private BookingInfo completedBooking(UUID consumerId, UUID providerUserId) {
        return Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), providerUserId)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "COMPLETED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
    }

    @Test
    void createReverse_byTheBookingProvider_storesTheReviewee() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerUserId);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(completedBooking(consumerId, providerUserId));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.createReverse(bookingId, 4, "great guest", authentication);

        assertEquals(ReviewDirection.PROVIDER_TO_CONSUMER, review.getDirection());
        assertEquals(consumerId, review.getRevieweeId());
        assertEquals(providerUserId, review.getReviewerId());
        assertEquals(providerUserId, review.getProviderId());
        // The SAME events as the forward path (the plan: same events).
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.ReviewCreatedEvent.class));
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.CacheInvalidationRequested.class));
    }

    @Test
    void createReverse_rejectsDuplicatePerBooking() {
        UUID bookingId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.createReverse(bookingId, 3, "dup", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReverse_rejectsAProviderWhoIsNotTheBookingProvider() {
        // The profile-existence gate is GONE with the profile resolution (the
        // §9 fix): the booking row is the ruling — a caller whose user id is
        // not the stored booking provider is denied, profile or no profile.
        // The no-profile caller is subsumed: his user id never matches the
        // booking's stored provider user id.
        UUID bookingId = Instancio.create(UUID.class);
        UUID bookingProviderUserId = Instancio.create(UUID.class);
        UUID otherProviderUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherProviderUserId);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(completedBooking(Instancio.create(UUID.class), bookingProviderUserId));

        assertThrows(AccessDeniedException.class,
                () -> service.createReverse(bookingId, 3, "not my booking", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReverse_rejectsNonCompletedBooking() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerUserId);
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(
                Instancio.of(BookingInfo.class)
                        .set(field(BookingInfo::providerId), providerUserId)
                        .set(field(BookingInfo::consumerId), Instancio.create(UUID.class))
                        .set(field(BookingInfo::status), "CONFIRMED")
                        .set(field(BookingInfo::priceCents), 5000L)
                        .set(field(BookingInfo::currency), "SAR")
                        .create());

        assertThrows(BadRequestException.class,
                () -> service.createReverse(bookingId, 3, "too early", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void create_allowsForwardWhenAReverseReviewAlreadyExists() {
        // Per-direction uniqueness: the SAME booking carries both directions,
        // one entry each — the forward create only conflicts with a forward
        // review, never with the reverse one.
        UUID bookingId = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER))
                .thenReturn(false);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(completedBooking(consumerId, providerId));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.create(bookingId, consumerId, 5, "after the reverse exists");

        assertEquals(ReviewDirection.CONSUMER_TO_PROVIDER, review.getDirection());
        assertNull(review.getRevieweeId());
    }

    @Test
    void createReverse_ratingFloorSharedWithTheForwardPath() {
        assertThrows(IllegalArgumentException.class, () -> Review.createReverse(
                Instancio.create(UUID.class), Instancio.create(UUID.class),
                Instancio.create(UUID.class), Instancio.create(UUID.class), 0, "bad"));
    }

    @Test
    void listByReviewee_returnsTheReverseReviewsAboutTheConsumer() {
        UUID consumerId = Instancio.create(UUID.class);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Review review = Review.createReverse(Instancio.create(UUID.class), Instancio.create(UUID.class),
                Instancio.create(UUID.class), consumerId, 4, "great guest");
        when(reviewRepository.findByRevieweeIdAndDirectionAndModerationStatus(
                        consumerId, ReviewDirection.PROVIDER_TO_CONSUMER,
                        ReviewModerationStatus.PUBLISHED, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));

        var result = service.listByReviewee(consumerId, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(ReviewDirection.PROVIDER_TO_CONSUMER, result.getContent().get(0).getDirection());
    }

    // -- W1: the mode gate and the organic path (§4.1/§4.3/§4.5) ----------

    private void openMode() {
        when(systemSettingsPort.getStringOrDefault(
                        org.mockito.ArgumentMatchers.eq(SystemSettingKeys.REVIEWS_MODE),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ReviewMode.OPEN.name());
    }

    private void organicGatesHappy(UUID reviewerId, UUID providerUserId) {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(reviewerId);
        when(providerLookupPort.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new ProviderSummary(
                        UUID.randomUUID(), "Reviewed", "VERIFIED", providerUserId)));
        when(userLookupPort.findById(reviewerId))
                .thenReturn(Optional.of(new UserSummary(reviewerId, null, "Reviewer", "CONSUMER",
                        clock.instant().minus(java.time.Duration.ofDays(8)), null, null)));
        when(systemSettingsPort.getIntOrDefault(
                        org.mockito.ArgumentMatchers.eq(SystemSettingKeys.REVIEWS_ORGANIC_DAILY_CAP),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(5);
        when(reviewRepository.countByReviewerIdAndOriginAndCreatedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        when(reviewRepository.existsByReviewerIdAndProviderIdAndOrigin(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(reviewRepository.countByReviewerIdAndOrigin(reviewerId, Review.ORIGIN_ORGANIC)).thenReturn(0L);
        when(reviewRepository.countByProviderIdAndOriginAndCreatedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        when(reviewRepository.existsOrganicWithNormalizedComment(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_bookingPathRefusedWhenModeIsOpen() {
        openMode();

        assertThrows(BadRequestException.class,
                () -> service.create(Instancio.create(UUID.class), Instancio.create(UUID.class),
                        4, "m"));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createOrganic_refusedWhenModeIsVerifiedOnly() {
        assertThrows(BadRequestException.class,
                () -> service.createOrganic(UUID.randomUUID(), null, 5, "x", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createOrganic_firstThreeReviewsQueueForModerationWithoutStatsEvent() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, providerUserId);

        Review review = service.createOrganic(
                Instancio.create(UUID.class), null, 5, "Great", authentication);

        assertEquals(Review.ORIGIN_ORGANIC, review.getOrigin());
        assertNull(review.getBookingId());
        assertEquals(providerUserId, review.getProviderId());
        assertEquals(ReviewModerationStatus.PENDING_REVIEW, review.getModerationStatus());
        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewCreatedEvent));
    }

    @Test
    void createOrganic_fourthReviewPublishesImmediatelyAndFiresTheEvent() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, providerUserId);
        when(reviewRepository.countByReviewerIdAndOrigin(reviewerId, Review.ORIGIN_ORGANIC))
                .thenReturn(3L);

        Review review = service.createOrganic(
                Instancio.create(UUID.class), null, 5, "Great", authentication);

        assertEquals(ReviewModerationStatus.PUBLISHED, review.getModerationStatus());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewCreatedEvent));
    }

    @Test
    void createOrganic_underageAccountRejected() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, providerUserId);
        when(userLookupPort.findById(reviewerId))
                .thenReturn(Optional.of(new UserSummary(reviewerId, null, "New", "CONSUMER",
                        clock.instant().minus(java.time.Duration.ofDays(2)), null, null)));

        assertThrows(BadRequestException.class, () -> service.createOrganic(
                Instancio.create(UUID.class), null, 5, "too new", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createOrganic_dailyCapAnswersTooManyRequests() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, providerUserId);
        when(reviewRepository.countByReviewerIdAndOriginAndCreatedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.eq(reviewerId),
                org.mockito.ArgumentMatchers.eq(Review.ORIGIN_ORGANIC),
                org.mockito.ArgumentMatchers.any())).thenReturn(5L);

        assertThrows(com.marketplace.shared.api.TooManyRequestsException.class,
                () -> service.createOrganic(
                        Instancio.create(UUID.class), null, 5, "capped", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createOrganic_duplicateAnswersConflict() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerUserId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, providerUserId);
        when(reviewRepository.existsByReviewerIdAndProviderIdAndOrigin(
                reviewerId, providerUserId, Review.ORIGIN_ORGANIC)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.createOrganic(
                Instancio.create(UUID.class), null, 5, "dup", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createOrganic_selfReviewRejected() {
        openMode();
        UUID reviewerId = Instancio.create(UUID.class);
        organicGatesHappy(reviewerId, reviewerId);

        assertThrows(BadRequestException.class, () -> service.createOrganic(
                Instancio.create(UUID.class), null, 5, "self", authentication));
        verify(reviewRepository, never()).save(any());
    }

    // -- W1: the moderation state machine (§4.5) ---------------------------

    @Test
    void approve_publishesQueuedReviewAndRepublishesTheEvent() {
        UUID reviewId = Instancio.create(UUID.class);
        Review queued = Review.createOrganic(Instancio.create(UUID.class),
                Instancio.create(UUID.class), null, 5, "queued");
        queued.queueForReview();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(queued));

        Review approved = service.approve(reviewId);

        assertEquals(ReviewModerationStatus.PUBLISHED, approved.getModerationStatus());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewCreatedEvent));
    }

    @Test
    void approve_ofAlreadyPublishedAnswersConflict() {
        UUID reviewId = Instancio.create(UUID.class);
        Review published = Review.createOrganic(Instancio.create(UUID.class),
                Instancio.create(UUID.class), null, 5, "already public");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(published));

        assertThrows(ConflictException.class, () -> service.approve(reviewId));
    }

    @Test
    void reject_hidesQueuedReviewAndPublishesTheUpdateEvent() {
        UUID reviewId = Instancio.create(UUID.class);
        Review queued = Review.createOrganic(Instancio.create(UUID.class),
                Instancio.create(UUID.class), null, 5, "queued");
        queued.queueForReview();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(queued));

        Review rejected = service.reject(reviewId);

        assertEquals(ReviewModerationStatus.HIDDEN_BY_MODERATOR, rejected.getModerationStatus());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewUpdatedEvent));
    }

    @Test
    void reject_ofPublishedAnswersConflict() {
        UUID reviewId = Instancio.create(UUID.class);
        Review published = Review.createOrganic(Instancio.create(UUID.class),
                Instancio.create(UUID.class), null, 5, "public");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(published));

        assertThrows(ConflictException.class, () -> service.reject(reviewId));
    }

    @Test
    void hideByModerator_flipsPublishedReviewAndReturnsItsAuthor() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID authorId = Instancio.create(UUID.class);
        Review published = Review.create(Instancio.create(UUID.class), authorId,
                Instancio.create(UUID.class), 5, "reported");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(published));

        java.util.Optional<UUID> author = service.hideByModerator(reviewId);

        assertEquals(authorId, author.orElseThrow());
        assertEquals(ReviewModerationStatus.HIDDEN_BY_MODERATOR, published.getModerationStatus());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewUpdatedEvent));
    }

    @Test
    void hideByModerator_pendingOrHiddenIsTheDocumentedSkip() {
        UUID reviewId = Instancio.create(UUID.class);
        Review queued = Review.createOrganic(Instancio.create(UUID.class),
                Instancio.create(UUID.class), null, 5, "queued");
        queued.queueForReview();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(queued));

        assertTrue(service.hideByModerator(reviewId).isEmpty());
        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.ReviewUpdatedEvent));
    }

    // -- W1: the helpful votes (§4.5) --------------------------------------

    @Test
    void voteHelpful_newVoteIsSavedOnce() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID voterId = Instancio.create(UUID.class);
        Review published = Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class),
                Instancio.create(UUID.class), 5, "good");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(published));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(voterId);
        when(reviewVoteRepository.existsByReviewIdAndVoterId(reviewId, voterId)).thenReturn(false);
        when(reviewVoteRepository.save(any(ReviewVote.class))).thenAnswer(inv -> inv.getArgument(0));

        service.voteHelpful(reviewId, authentication);

        verify(reviewVoteRepository).save(org.mockito.ArgumentMatchers.argThat((ReviewVote vote) ->
                reviewId.equals(vote.getReviewId()) && voterId.equals(vote.getVoterId())));
    }

    @Test
    void voteHelpful_ownReviewAnswersBadRequestAndDuplicateAnswersConflict() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID authorId = Instancio.create(UUID.class);
        UUID voterId = Instancio.create(UUID.class);
        Review published = Review.create(Instancio.create(UUID.class), authorId,
                Instancio.create(UUID.class), 5, "good");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(published));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);

        assertThrows(BadRequestException.class,
                () -> service.voteHelpful(reviewId, authentication));

        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(voterId);
        when(reviewVoteRepository.existsByReviewIdAndVoterId(reviewId, voterId)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.voteHelpful(reviewId, authentication));
        verify(reviewVoteRepository, never()).save(any());
    }
}
