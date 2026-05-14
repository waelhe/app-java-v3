package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DisputeServiceTest {

    private final DisputeRepository repository = mock(DisputeRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final DisputeService service = new DisputeService(repository, currentUserProvider, bookingProvider);
    private final UUID userId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final BookingInfo bookingInfo = new BookingInfo(
            UUID.randomUUID(), userId, "CONFIRMED", 2000L, "SAR", Instant.now(), Instant.now());

    @Test
    void openDisputeForParticipant() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        Dispute dispute = service.open(bookingId, "late arrival", authentication);

        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void listForBookingReturnsDisputes() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        service.listForBooking(bookingId, authentication);

        verify(repository).findByBookingId(bookingId);
    }

    @Test
    void listForBookingAllowsAdmin() {
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        service.listForBooking(bookingId, authentication);

        verify(repository).findByBookingId(bookingId);
    }

    @Test
    void resolveUpdatesStatus() {
        var disputeId = UUID.randomUUID();
        var dispute = Dispute.open(bookingId, userId, "reason");
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        service.resolve(disputeId, authentication);

        verify(repository).findById(disputeId);
    }

    @Test
    void resolveThrowsWhenNotAdmin() {
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.resolve(UUID.randomUUID(), authentication));
    }

    @Test
    void resolveThrowsWhenNotFound() {
        var disputeId = UUID.randomUUID();
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(repository.findById(disputeId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.resolve(disputeId, authentication));
    }
}
