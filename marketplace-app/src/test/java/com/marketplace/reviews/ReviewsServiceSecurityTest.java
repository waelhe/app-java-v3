package com.marketplace.reviews;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
}
