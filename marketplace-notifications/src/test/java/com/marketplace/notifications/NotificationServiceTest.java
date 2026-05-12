package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    @Test
    void onBookingCreatedCreatesTwoNotifications() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        NotificationService service = new NotificationService(repository, bookingProvider, paymentIntentLookupPort, currentUserProvider);

        UUID bookingId = UUID.randomUUID();
        BookingInfo info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 1000L, "SAR", Instant.now(), Instant.now());
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(info);

        service.onBookingCreated(bookingId);

        verify(repository, times(2)).save(any(Notification.class));
    }

    @Test
    void markReadMarksNotificationForOwner() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = new NotificationService(repository, bookingProvider, paymentIntentLookupPort, currentUserProvider);

        UUID userId = UUID.randomUUID();
        Notification notification = Notification.create(userId, "BOOKING_CREATED", "msg");
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        Notification updated = service.markAsRead(notification.getId(), authentication);

        assertThat(updated.isRead()).isTrue();
    }

    @Test
    void onPaymentStateChangedCreatesNotificationsForConsumerAndProvider() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        NotificationService service = new NotificationService(repository, bookingProvider, paymentIntentLookupPort, currentUserProvider);

        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(new PaymentIntentDetails(paymentIntentId, bookingId, consumerId, "SUCCEEDED")));
        when(bookingProvider.getBookingInfo(bookingId))
                .thenReturn(new BookingInfo(providerId, consumerId, "CONFIRMED", 1000L, "SAR", Instant.now(), Instant.now()));

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(repository, times(2)).save(any(Notification.class));
    }
}
