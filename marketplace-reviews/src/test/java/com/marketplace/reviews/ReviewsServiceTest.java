package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewsServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private ReviewsService service;

    @BeforeEach
    void setUp() {
        service = new ReviewsService(reviewRepository, currentUserProvider, eventPublisher, bookingParticipantProvider);
    }

    @Test
    void create_validReview_forCompletedBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(providerId, consumerId, "COMPLETED", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.create(bookingId, consumerId, 4, "Great service");

        assertEquals(4, review.getRating());
        assertEquals(providerId, review.getProviderId());
    }

    @Test
    void create_rejectsInvalidRating() {
        assertThrows(IllegalArgumentException.class,
                () -> Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, "bad"));
    }

    @Test
    void create_rejectsDuplicateReview() {
        UUID bookingId = UUID.randomUUID();
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(true);
        assertThrows(IllegalStateException.class,
                () -> service.create(bookingId, UUID.randomUUID(), 3, "dup"));
    }

    @Test
    void create_rejectsNonConsumer() {
        UUID bookingId = UUID.randomUUID();
        UUID actualConsumerId = UUID.randomUUID();
        UUID differentUserId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(UUID.randomUUID(), actualConsumerId, "COMPLETED", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.create(bookingId, differentUserId, 3, "hacked"));
    }

    @Test
    void create_rejectsNonCompletedBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(UUID.randomUUID(), consumerId, "CONFIRMED", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.create(bookingId, consumerId, 3, "too early"));
    }

    @Test
    void update_changesRatingAndComment() {
        UUID id = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), reviewerId, UUID.randomUUID(), 3, "ok");
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(reviewerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Review updated = service.update(id, 5, "excellent", authentication);
        assertEquals(5, updated.getRating());
    }

    @Test
    void update_throwsWhenNotOwner() {
        UUID id = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, "ok");
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.update(id, 5, "excellent", authentication));
    }
}
