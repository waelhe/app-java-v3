package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock
    private DisputeRepository repository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private BookingParticipantProvider bookingParticipantProvider;

    @InjectMocks
    private DisputeService disputeService;

    private final Authentication authentication = mock(Authentication.class);

    @Test
    void openDisputeForParticipant() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        BookingInfo info = new BookingInfo(userId, UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        Dispute dispute = disputeService.open(bookingId, "late arrival", authentication);

        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void listForBooking_asParticipant_returnsList() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        BookingInfo info = new BookingInfo(UUID.randomUUID(), userId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        Dispute dispute = Dispute.open(bookingId, userId, "noise");
        when(repository.findByBookingId(bookingId)).thenReturn(List.of(dispute));

        List<Dispute> result = disputeService.listForBooking(bookingId, authentication);

        assertThat(result).containsExactly(dispute);
    }

    @Test
    void listForBooking_asAdmin_returnsList() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(mock(BookingInfo.class));
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "noise");
        when(repository.findByBookingId(bookingId)).thenReturn(List.of(dispute));

        List<Dispute> result = disputeService.listForBooking(bookingId, authentication);

        assertThat(result).containsExactly(dispute);
    }

    @Test
    void listForBooking_asNonParticipant_throws() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        BookingInfo info = new BookingInfo(UUID.randomUUID(), otherUserId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);

        assertThrows(AccessDeniedException.class,
                () -> disputeService.listForBooking(bookingId, authentication));
    }

    @Test
    void resolve_asAdmin_succeeds() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        Dispute result = disputeService.resolve(disputeId, authentication);

        verify(repository).findById(disputeId);
    }

    @Test
    void resolve_notFound_throws() {
        UUID disputeId = UUID.randomUUID();
        when(repository.findById(disputeId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> disputeService.resolve(disputeId, authentication));
    }
}
