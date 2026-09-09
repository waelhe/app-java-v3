package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ReviewUpdatedEvent;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

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
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final Authentication authentication = mock(Authentication.class);
    private ReviewsService service;

    @BeforeEach
    void setUp() {
        service = new ReviewsService(reviewRepository, currentUserProvider, eventPublisher,
                bookingParticipantProvider, providerLookupPort);
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
        when(reviewRepository.findByProviderIdAndDirection(providerId, ReviewDirection.CONSUMER_TO_PROVIDER, pageable))
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
        when(reviewRepository.findByReviewerId(reviewerId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));

        var result = service.listByReviewer(reviewerId, pageable);

        assertEquals(1, result.getTotalElements());
    }

    // -- L21: reply ------------------------------------------------------

    private Review reviewFor(UUID providerId) {
        return Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class),
                providerId, 4, "Good");
    }

    @Test
    void reply_targetProviderSetsReplyAndInvalidatesCache() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID ownerUserId = Instancio.create(UUID.class);
        Review review = reviewFor(providerId);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(ownerUserId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Owner", "VERIFIED", ownerUserId)));

        Review result = service.reply(reviewId, "Thanks for the feedback", authentication);

        assertEquals("Thanks for the feedback", result.getReply());
        assertNotNull(result.getRepliedAt());
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) ->
                        ev instanceof com.marketplace.shared.api.CacheInvalidationRequested));
    }

    @Test
    void reply_throwsWhenCallerHasNoProviderProfile() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(reviewFor(Instancio.create(UUID.class))));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerLookupPort.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> service.reply(reviewId, "x", authentication));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reply_throwsForDifferentProvider() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID reviewedProviderId = Instancio.create(UUID.class);
        UUID otherProviderId = Instancio.create(UUID.class);
        UUID otherOwnerUserId = Instancio.create(UUID.class);
        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(reviewFor(reviewedProviderId)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherOwnerUserId);
        when(providerLookupPort.findByUserId(otherOwnerUserId)).thenReturn(Optional.of(
                new ProviderSummary(otherProviderId, "Other", "VERIFIED", otherOwnerUserId)));

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.reply(reviewId, "not mine", authentication));

        assertTrue(ex.getMessage().contains("reviewed provider"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reply_throwsWhenAlreadyReplied() {
        UUID reviewId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID ownerUserId = Instancio.create(UUID.class);
        Review review = reviewFor(providerId);
        review.reply("first");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(ownerUserId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Owner", "VERIFIED", ownerUserId)));

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

    private BookingInfo completedBooking(UUID consumerId, UUID providerId) {
        return Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), providerId)
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
        UUID providerId = Instancio.create(UUID.class);
        UUID ownerUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(ownerUserId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Owner", "VERIFIED", ownerUserId)));
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(completedBooking(consumerId, providerId));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.createReverse(bookingId, 4, "great guest", authentication);

        assertEquals(ReviewDirection.PROVIDER_TO_CONSUMER, review.getDirection());
        assertEquals(consumerId, review.getRevieweeId());
        assertEquals(ownerUserId, review.getReviewerId());
        assertEquals(providerId, review.getProviderId());
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
    void createReverse_rejectsCallerWithoutAProviderProfile() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerLookupPort.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> service.createReverse(bookingId, 3, "no profile", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReverse_rejectsAProviderWhoIsNotTheBookingProvider() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID bookingProviderId = Instancio.create(UUID.class);
        UUID otherProviderId = Instancio.create(UUID.class);
        UUID otherOwnerUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherOwnerUserId);
        when(providerLookupPort.findByUserId(otherOwnerUserId)).thenReturn(Optional.of(
                new ProviderSummary(otherProviderId, "Other", "VERIFIED", otherOwnerUserId)));
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(completedBooking(Instancio.create(UUID.class), bookingProviderId));

        assertThrows(AccessDeniedException.class,
                () -> service.createReverse(bookingId, 3, "not my booking", authentication));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReverse_rejectsNonCompletedBooking() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID ownerUserId = Instancio.create(UUID.class);
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.PROVIDER_TO_CONSUMER))
                .thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(ownerUserId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Owner", "VERIFIED", ownerUserId)));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(
                Instancio.of(BookingInfo.class)
                        .set(field(BookingInfo::providerId), providerId)
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
        when(reviewRepository.findByRevieweeIdAndDirection(consumerId, ReviewDirection.PROVIDER_TO_CONSUMER, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(review)));

        var result = service.listByReviewee(consumerId, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(ReviewDirection.PROVIDER_TO_CONSUMER, result.getContent().get(0).getDirection());
    }
}
