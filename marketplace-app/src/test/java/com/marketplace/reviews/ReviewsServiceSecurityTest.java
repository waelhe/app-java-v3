package com.marketplace.reviews;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { ReviewsService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class ReviewsServiceSecurityTest {

    @Autowired
    private ReviewsService reviewsService;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private BookingParticipantProvider bookingParticipantProvider;

    @Test
    @WithMockUser(roles = "USER")
    void create_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.create(UUID.randomUUID(), UUID.randomUUID(), 5, "Great"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void update_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.update(UUID.randomUUID(), 5, "Great", null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void create_whenConsumer_thenInvokes() {
        UUID bookingId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(new BookingInfo(providerId, reviewerId, "COMPLETED", 1000L, "SAR", at, at));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Review result = reviewsService.create(bookingId, reviewerId, 5, "Great");

        assertThat(result.getRating()).isEqualTo(5);
        assertThat(result.getReviewerId()).isEqualTo(reviewerId);
        verify(reviewRepository).save(any(Review.class));
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void update_whenConsumer_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID reviewerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), reviewerId, providerId, 5, "Great");
        when(reviewRepository.findById(review.getId())).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(reviewerId);

        Review result = reviewsService.update(review.getId(), 4, "Updated", authentication);

        assertThat(result.getRating()).isEqualTo(4);
    }
}
