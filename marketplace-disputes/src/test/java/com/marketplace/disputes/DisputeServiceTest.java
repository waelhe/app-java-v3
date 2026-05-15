package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DisputeServiceTest {

    private final DisputeRepository repository = mock(DisputeRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final DisputeService service = new DisputeService(repository, currentUserProvider, bookingProvider);

    @Test
    void openDisputeForParticipant() {
        UUID bookingId = create(UUID.class);
        UUID userId = create(UUID.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        BookingInfo info = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), userId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        Dispute dispute = service.open(bookingId, "late arrival", authentication);

        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void openDispute_rejectsNonParticipant() {
        UUID bookingId = create(UUID.class);
        UUID userId = create(UUID.class);
        UUID otherId = create(UUID.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(otherId);
        BookingInfo info = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), userId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(info);
        assertThrows(AccessDeniedException.class,
                () -> service.open(bookingId, "nope", authentication));
    }

    @Test
    void listForBooking_returnsForParticipant() {
        UUID bookingId = create(UUID.class);
        UUID userId = create(UUID.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        BookingInfo info = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), userId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.findByBookingId(bookingId)).thenReturn(List.of());
        assertThat(service.listForBooking(bookingId, authentication)).isEmpty();
    }

    @Test
    void listForBooking_allowsAdmin() {
        UUID bookingId = create(UUID.class);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(repository.findByBookingId(bookingId)).thenReturn(List.of());
        assertThat(service.listForBooking(bookingId, authentication)).isEmpty();
        verify(bookingProvider, never()).getBookingInfo(any());
    }

    @Test
    void resolve_allowsAdmin() {
        UUID id = create(UUID.class);
        Dispute dispute = Dispute.open(create(UUID.class), create(UUID.class), "reason");
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(repository.findById(id)).thenReturn(Optional.of(dispute));
        Dispute resolved = service.resolve(id, authentication);
        assertThat(resolved).isSameAs(dispute);
        assertThat(resolved.getBookingId()).isNotNull();
    }

    @Test
    void resolve_rejectsNonAdmin() {
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.resolve(create(UUID.class), authentication));
    }

    @Test
    void resolve_throwsWhenNotFound() {
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.resolve(create(UUID.class), authentication));
    }

    @Test
    void dispute_openCreatesDispute() {
        UUID bookingId = create(UUID.class);
        Dispute dispute = Dispute.open(bookingId, create(UUID.class), "reason");
        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
        assertThat(dispute.getId()).isNotNull();
    }
}
