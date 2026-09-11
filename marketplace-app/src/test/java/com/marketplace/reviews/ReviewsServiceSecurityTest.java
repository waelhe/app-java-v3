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

    // The ProviderLookupPort bean is GONE (the §9 surgical gate fix): the
    // reply/createReverse gates read the ruling from the row itself
    // (users.id, A1) — no profile resolution anywhere in the service.

    @Test
    @WithMockUser(roles = "USER")
    void create_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.create(UUID.randomUUID(), UUID.randomUUID(), 5, "Great"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void reply_whenNotProvider_thenAccessDenied() {
        // L21: the method-security gate on the provider side of the two-way
        // review — a non-PROVIDER principal never reaches the ownership logic.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.reply(UUID.randomUUID(), "Thanks", null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void update_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.update(UUID.randomUUID(), 5, "Great", null));
    }

    /**
     * I8: update's role gate is the coarse pre-filter; the real guard is
     * ownership. A PROVIDER (a reverse-review author in principle) who is
     * NOT the review's author is still denied — by the ownership check.
     */
    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void update_whenProviderButNotAuthor_thenAccessDeniedByOwnership() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), reviewerId, UUID.randomUUID(), 5, "Great");
        when(reviewRepository.findById(review.getId())).thenReturn(Optional.of(review));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class)))
                .thenReturn(UUID.randomUUID()); // not the author
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.update(review.getId(), 4, "hijack", authentication));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void createReverse_whenNotProvider_thenAccessDenied() {
        // I8: the reverse write is the provider's surface — a CONSUMER
        // principal never reaches the ownership logic.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> reviewsService.createReverse(UUID.randomUUID(), 5, "Great", null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void create_whenConsumer_thenInvokes() {
        UUID bookingId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        when(reviewRepository.existsByBookingIdAndDirection(bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).thenReturn(false);
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
