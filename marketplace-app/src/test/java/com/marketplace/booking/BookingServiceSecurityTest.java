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

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void create_whenConsumer_thenInvokes() {
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-09-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-09-01T11:00:00Z");
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
        when(availabilityPort.isAvailable(providerId, startsAt, endsAt)).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking result = bookingService.create(consumerId, listingId, startsAt, endsAt, "notes");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void confirm_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-09-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-09-01T11:00:00Z");
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 1000L, startsAt, endsAt, "notes");
        UUID bookingId = booking.getId();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(providerId);

        Booking result = bookingService.confirm(bookingId, authentication);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(availabilityPort).bookSlot(providerId, startsAt, endsAt);
    }

    @Test
    @WithMockUser(roles = "PROVIDER", username = "provider")
    void complete_whenProvider_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 1000L,
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"), "notes");
        booking.confirm();
        UUID bookingId = booking.getId();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(providerId);

        Booking result = bookingService.complete(bookingId, authentication);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void cancel_whenConsumer_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-09-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-09-01T11:00:00Z");
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 1000L, startsAt, endsAt, "notes");
        UUID bookingId = booking.getId();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(consumerId);

        Booking result = bookingService.cancel(bookingId, authentication);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(availabilityPort).releaseSlot(providerId, startsAt, endsAt);
    }
}
