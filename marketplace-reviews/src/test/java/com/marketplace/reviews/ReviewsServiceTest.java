package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
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
    private final Authentication authentication = mock(Authentication.class);
    private ReviewsService service;

    @BeforeEach
    void setUp() {
        service = new ReviewsService(reviewRepository, currentUserProvider, eventPublisher, bookingParticipantProvider);
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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(true);
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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);

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
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);

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
        when(reviewRepository.findByProviderId(providerId, pageable))
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
}
