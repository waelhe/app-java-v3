package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
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
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
    private final PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final NotificationService service = new NotificationService(repository, bookingProvider, paymentIntentLookupPort, currentUserProvider);

    @Test
    void onBookingCreatedCreatesTwoNotifications() {
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
        Authentication authentication = mock(Authentication.class);
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
    void markRead_throwsWhenNotOwner() {
        Authentication authentication = mock(Authentication.class);
        Notification notification = of(Notification.class)
                .set(field(Notification::getRecipientId), create(UUID.class))
                .set(field(Notification::getType), "BOOKING_CREATED")
                .set(field(Notification::getMessage), "msg")
                .create();
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.markAsRead(notification.getId(), authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markRead_allowsAdmin() {
        Authentication authentication = mock(Authentication.class);
        Notification notification = of(Notification.class)
                .set(field(Notification::getRecipientId), create(UUID.class))
                .set(field(Notification::getType), "BOOKING_CREATED")
                .set(field(Notification::getMessage), "msg")
                .create();
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        assertThat(service.markAsRead(notification.getId(), authentication).isRead()).isTrue();
    }

    @Test
    void markRead_throwsWhenNotFound() {
        Authentication authentication = mock(Authentication.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markAsRead(create(UUID.class), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onPaymentStateChangedCreatesNotificationsForConsumerAndProvider() {
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

    @Test
    void onPaymentStateChanged_ignoresWhenIntentNotFound() {
        when(paymentIntentLookupPort.findById(any())).thenReturn(Optional.empty());
        service.onPaymentStateChanged(create(UUID.class), "COMPLETED");
        verifyNoInteractions(repository);
    }

    @Test
    void getMyNotificationsReturnsNotificationsForCurrentUser() {
        Authentication authentication = mock(Authentication.class);
        UUID userId = create(UUID.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(repository.findByRecipientIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        assertThat(service.getMyNotifications(authentication)).isEmpty();
    }

    @Test
    void notification_markRead_changesFlag() {
        Notification notification = Notification.create(create(UUID.class), "TEST", "msg");
        assertThat(notification.isRead()).isFalse();
        notification.markRead();
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void notification_createSetsFields() {
        UUID recipientId = create(UUID.class);
        Notification notification = Notification.create(recipientId, "TYPE", "message text");
        assertThat(notification.getRecipientId()).isEqualTo(recipientId);
        assertThat(notification.getType()).isEqualTo("TYPE");
        assertThat(notification.getMessage()).isEqualTo("message text");
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getId()).isNotNull();
    }
}
