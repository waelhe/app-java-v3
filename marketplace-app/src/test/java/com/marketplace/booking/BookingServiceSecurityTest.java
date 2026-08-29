package com.marketplace.booking;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.ListingPriceProvider;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { BookingService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class BookingServiceSecurityTest {

    @Autowired
    private BookingService bookingService;

    @MockitoBean
    private BookingRepository bookingRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private ListingPriceProvider listingPriceProvider;

    @MockitoBean
    private AvailabilityPort availabilityPort;

    @Test
    @WithMockUser(roles = "USER")
    void create_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> bookingService.create(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now(), "notes"));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void confirm_whenNotProviderOrAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> bookingService.confirm(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void cancel_whenNotConsumerOrProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> bookingService.cancel(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void complete_whenNotProviderOrAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> bookingService.complete(UUID.randomUUID(), null));
    }
}
