package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DisputeServiceTest {

    @Test
    void openDisputeForParticipant() {
        DisputeRepository repository = mock(DisputeRepository.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        Authentication authentication = mock(Authentication.class);
        DisputeService service = new DisputeService(repository, currentUserProvider, bookingProvider);

        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        BookingInfo info = new BookingInfo(UUID.randomUUID(), userId, "CONFIRMED", 2000L, "SAR", Instant.now(), Instant.now());
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        Dispute dispute = service.open(bookingId, "late arrival", authentication);

        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
    }
}
