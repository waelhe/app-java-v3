package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
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
}
