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

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
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

        UUID bookingId = create(UUID.class);
        BookingInfo info = of(BookingInfo.class)
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
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

        UUID userId = create(UUID.class);
        Notification notification = of(Notification.class)
                .set(field(Notification::getRecipientId), userId)
                .set(field(Notification::getType), "BOOKING_CREATED")
                .set(field(Notification::getMessage), "msg")
                .create();
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

        UUID paymentIntentId = create(UUID.class);
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        UUID providerId = create(UUID.class);

        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(of(PaymentIntentDetails.class)
                        .set(field(PaymentIntentDetails::paymentIntentId), paymentIntentId)
                        .set(field(PaymentIntentDetails::bookingId), bookingId)
                        .set(field(PaymentIntentDetails::consumerId), consumerId)
                        .create()));
        when(bookingProvider.getBookingInfo(bookingId))
                .thenReturn(of(BookingInfo.class)
                        .set(field(BookingInfo::providerId), providerId)
                        .set(field(BookingInfo::consumerId), consumerId)
                        .set(field(BookingInfo::status), "CONFIRMED")
                        .set(field(BookingInfo::priceCents), 5000L)
                        .set(field(BookingInfo::currency), "SAR")
                        .create());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(repository, times(2)).save(any(Notification.class));
    }
}
